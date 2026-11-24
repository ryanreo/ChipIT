package com.chipit.app.metrics

import android.content.Context
import android.os.PowerManager
import com.chipit.app.data.db.BenchmarkRunDao
import com.chipit.app.data.db.BenchmarkRunEntity
import com.chipit.app.data.db.ModelEntity
import com.chipit.app.data.db.SessionDao
import com.chipit.app.data.db.SessionEntity
import com.chipit.app.engine.InferenceConfig
import com.chipit.app.engine.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class BenchmarkPrompt(
    val id: String,
    val title: String,
    val prompt: String
)

data class BenchmarkProgress(
    val isRunning: Boolean = false,
    val currentPromptIndex: Int = 0,
    val totalPrompts: Int = 0,
    val currentTitle: String = "",
    val thermalWarning: String? = null
)

@Serializable
data class BenchmarkExportPayload(
    val exportedAt: Long,
    val runs: List<BenchmarkExportRow>
)

@Serializable
data class BenchmarkExportRow(
    val id: Long,
    val modelName: String,
    val suiteName: String,
    val backend: String,
    val avgTtftMs: Long,
    val avgPrefillTps: Double,
    val avgDecodeTps: Double,
    val peakRamMb: Int,
    val batteryPctPer1kTok: Double,
    val maxThermalStatus: Int,
    val createdAt: Long
)

class BenchmarkRunner(
    private val context: Context,
    private val benchmarkRunDao: BenchmarkRunDao,
    private val sessionDao: SessionDao,
    private val metricsCollector: MetricsCollector
) {
    private val _progress = MutableStateFlow(BenchmarkProgress())
    val progress: StateFlow<BenchmarkProgress> = _progress.asStateFlow()

    val standardSuite: List<BenchmarkPrompt> = listOf(
        BenchmarkPrompt(
            id = "reasoning",
            title = "Short Reasoning",
            prompt = "A mobile phone has 8 GB RAM and loads a 2.1 GB Q4_K_M model plus 256 MB KV cache. Explain step-by-step how much RAM remains."
        ),
        BenchmarkPrompt(
            id = "summarize",
            title = "Summarization",
            prompt = "Summarize in 3 concise bullet points why on-device LLM inference improves privacy, latency, and offline reliability."
        ),
        BenchmarkPrompt(
            id = "json_tool",
            title = "JSON Tool Calling",
            prompt = """Output only valid JSON matching {"tool":"open_app","args":{"package_name":"com.spotify.music"}} for opening Spotify."""
        ),
        BenchmarkPrompt(
            id = "prefill",
            title = "Long-Context Prefill",
            prompt = "Analyze these telemetry metrics and identify bottlenecks: " +
                "load=1420ms, ttft=890ms, prefill=24.5tok/s, decode=11.2tok/s, pss=3120MB, thermal=MODERATE. ".repeat(6)
        )
    )

    fun observeRuns(): Flow<List<BenchmarkRunEntity>> = benchmarkRunDao.observeAll()

    suspend fun runStandardSuite(
        model: ModelEntity,
        engine: InferenceEngine,
        config: InferenceConfig
    ): Result<BenchmarkRunEntity> = runCatching {
        _progress.value = BenchmarkProgress(
            isRunning = true,
            currentPromptIndex = 0,
            totalPrompts = standardSuite.size,
            currentTitle = "Loading ${model.name} on ${config.backend.displayName}"
        )

        val loadRes = engine.loadModel(model, config).getOrThrow()
        val sessionId = sessionDao.insert(
            SessionEntity(
                modelId = model.id,
                backend = config.backend.name,
                threads = config.threads,
                gpuLayers = config.gpuLayers,
                contextLen = config.contextLength,
                loadTimeMs = loadRes.loadTimeMs
            )
        )

        val startSnap = metricsCollector.captureSnapshot()
        val ttfts = mutableListOf<Long>()
        val prefills = mutableListOf<Double>()
        val decodes = mutableListOf<Double>()
        var peakRam = startSnap.pssRamMb
        var maxThermal = startSnap.thermalStatus
        var totalTokens = 0

        for ((idx, item) in standardSuite.withIndex()) {
            val snapBefore = metricsCollector.captureSnapshot()
            if (snapBefore.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
                _progress.value = _progress.value.copy(
                    thermalWarning = "Paused: device thermal status is CRITICAL (${snapBefore.batteryTempC}°C)"
                )
                break
            }
            _progress.value = BenchmarkProgress(
                isRunning = true,
                currentPromptIndex = idx + 1,
                totalPrompts = standardSuite.size,
                currentTitle = item.title
            )

            var lastTtft = 0L
            var lastPrefill = 0.0
            var lastDecode = 0.0
            var genToks = 0

            engine.generateStream(
                systemPrompt = "You are a concise benchmark assistant.",
                userPrompt = item.prompt,
                config = config.copy(maxTokens = 128)
            ).collect { ev ->
                if (ev.ttftMs > 0) lastTtft = ev.ttftMs
                if (ev.prefillTps > 0) lastPrefill = ev.prefillTps
                if (ev.decodeTps > 0) lastDecode = ev.decodeTps
                if (ev.generatedTokens > genToks) genToks = ev.generatedTokens
            }

            totalTokens += genToks
            if (lastTtft > 0) ttfts += lastTtft
            if (lastPrefill > 0) prefills += lastPrefill
            if (lastDecode > 0) decodes += lastDecode

            val sample = metricsCollector.recordTurnSample(
                sessionId = sessionId,
                ttftMs = lastTtft,
                prefillTps = lastPrefill,
                decodeTps = lastDecode,
                batteryMahDelta = (genToks * 0.04)
            )
            if (sample.pssRamMb > peakRam) peakRam = sample.pssRamMb
            if (sample.thermalStatus > maxThermal) maxThermal = sample.thermalStatus
        }

        val endSnap = metricsCollector.captureSnapshot()
        sessionDao.closeSession(sessionId)

        val batteryDrop = (startSnap.batteryLevelPct - endSnap.batteryLevelPct).coerceAtLeast(0)
        val pctPer1k = if (totalTokens > 0 && batteryDrop > 0) {
            (batteryDrop.toDouble() / totalTokens.toDouble()) * 1000.0
        } else {
            // Estimated from current draw if battery % integer didn't tick during short suite
            ((endSnap.batteryCurrentMa / 4500.0) * 1.5).coerceAtLeast(0.1)
        }

        val run = BenchmarkRunEntity(
            modelId = model.id,
            modelName = model.name,
            suiteName = "Standard Suite (${standardSuite.size} prompts)",
            backend = config.backend.name,
            configJson = """{"threads":${config.threads},"gpuLayers":${config.gpuLayers},"ctx":${config.contextLength}}""",
            avgTtftMs = if (ttfts.isNotEmpty()) ttfts.average().toLong() else 0L,
            avgPrefillTps = if (prefills.isNotEmpty()) prefills.average() else 0.0,
            avgDecodeTps = if (decodes.isNotEmpty()) decodes.average() else 0.0,
            peakRamMb = peakRam,
            batteryPctPer1kTok = pctPer1k,
            maxThermalStatus = maxThermal
        )
        val id = benchmarkRunDao.insert(run)
        _progress.value = BenchmarkProgress(isRunning = false)
        run.copy(id = id)
    }.also {
        if (it.isFailure) _progress.value = BenchmarkProgress(isRunning = false)
    }

    suspend fun exportToFile(asJson: Boolean): File {
        val runs = benchmarkRunDao.getAll()
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val file = File(exportDir, "chipit_benchmark_$ts.${if (asJson) "json" else "csv"}")

        if (asJson) {
            val payload = BenchmarkExportPayload(
                exportedAt = ts,
                runs = runs.map {
                    BenchmarkExportRow(
                        id = it.id,
                        modelName = it.modelName,
                        suiteName = it.suiteName,
                        backend = it.backend,
                        avgTtftMs = it.avgTtftMs,
                        avgPrefillTps = it.avgPrefillTps,
                        avgDecodeTps = it.avgDecodeTps,
                        peakRamMb = it.peakRamMb,
                        batteryPctPer1kTok = it.batteryPctPer1kTok,
                        maxThermalStatus = it.maxThermalStatus,
                        createdAt = it.createdAt
                    )
                }
            )
            val json = Json { prettyPrint = true }
            file.writeText(json.encodeToString(payload))
        } else {
            val sb = StringBuilder()
            sb.appendLine("id,model_name,suite_name,backend,avg_ttft_ms,avg_prefill_tps,avg_decode_tps,peak_ram_mb,battery_pct_per_1k_tok,max_thermal_status,created_at")
            for (r in runs) {
                sb.appendLine("${r.id},\"${r.modelName}\",\"${r.suiteName}\",${r.backend},${r.avgTtftMs},${"%.2f".format(r.avgPrefillTps)},${"%.2f".format(r.avgDecodeTps)},${r.peakRamMb},${"%.2f".format(r.batteryPctPer1kTok)},${r.maxThermalStatus},${r.createdAt}")
            }
            file.writeText(sb.toString())
        }
        return file
    }
}

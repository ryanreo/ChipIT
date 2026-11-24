package com.chipit.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chipit.app.ActiveRuntimeState
import com.chipit.app.ChipITApplication
import com.chipit.app.data.db.ComputeBackend
import com.chipit.app.data.db.ModelEntity
import com.chipit.app.data.db.ModelFormat
import com.chipit.app.data.db.SessionEntity
import com.chipit.app.engine.InferenceConfig
import com.chipit.app.model.DeviceFitStatus
import com.chipit.app.model.DownloadProgress
import com.chipit.app.model.HfModelFileCandidate
import com.chipit.app.model.HfModelSummary
import com.chipit.app.model.ModelDownloadWorker
import com.chipit.app.model.ModelManager
import com.chipit.app.service.ChipITForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val meta: String? = null
)

class ChipITViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ChipITApplication
    private val c = app.container

    val localModels = c.modelManager.observeModels()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()
    private var lastReportedWorkId: String? = null
    val activeRuntime = c.activeRuntime
    val liveHardware = c.metricsCollector.liveSnapshot
    val benchmarkRuns = c.benchmarkRunner.observeRuns()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val benchmarkProgress = c.benchmarkRunner.progress
    val metricSamples = c.database.metricSampleDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val agentState = c.agentOrchestrator.state
    val agentLogs = c.agentOrchestrator.observeActionLogs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _hfResults = MutableStateFlow<List<HfModelSummary>>(emptyList())
    val hfResults: StateFlow<List<HfModelSummary>> = _hfResults.asStateFlow()

    private val _selectedRepo = MutableStateFlow<HfModelSummary?>(null)
    val selectedRepo: StateFlow<HfModelSummary?> = _selectedRepo.asStateFlow()

    private val _repoFiles = MutableStateFlow<List<HfModelFileCandidate>>(emptyList())
    val repoFiles: StateFlow<List<HfModelFileCandidate>> = _repoFiles.asStateFlow()

    private val _hfBusy = MutableStateFlow(false)
    val hfBusy: StateFlow<Boolean> = _hfBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _liveTokensPerSec = MutableStateFlow(0.0)
    val liveTokensPerSec: StateFlow<Double> = _liveTokensPerSec.asStateFlow()

    private val _lastTtftMs = MutableStateFlow(0L)
    val lastTtftMs: StateFlow<Long> = _lastTtftMs.asStateFlow()

    private val _agentMode = MutableStateFlow(false)
    val agentMode: StateFlow<Boolean> = _agentMode.asStateFlow()

    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                c.metricsCollector.captureSnapshot()
                delay(2000)
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosByTagFlow(ModelManager.WORK_TAG)
                .collect { infos ->
                    val active = infos.firstOrNull {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    if (active != null) {
                        val file = active.progress.getString(ModelDownloadWorker.KEY_PROGRESS_FILE)
                            ?: "Model download"
                        val downloaded = active.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_DOWNLOADED, 0L)
                        val total = active.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_TOTAL, 0L)
                        _downloadProgress.value = DownloadProgress(file, downloaded, total)
                    } else {
                        _downloadProgress.value = null
                        val finished = infos.lastOrNull {
                            it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED
                        }
                        if (finished != null && finished.id.toString() != lastReportedWorkId) {
                            lastReportedWorkId = finished.id.toString()
                            if (finished.state == WorkInfo.State.SUCCEEDED) {
                                val name = finished.outputData.getString(ModelDownloadWorker.KEY_RESULT_NAME) ?: "Model"
                                val quant = finished.outputData.getString(ModelDownloadWorker.KEY_RESULT_QUANT) ?: ""
                                _statusBanner.value = "Downloaded $name ($quant)".trim()
                            } else {
                                val err = finished.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                if (!err.isNullOrBlank()) _statusBanner.value = err
                            }
                        }
                    }
                }
        }
    }

    fun getDeviceResources() = c.modelManager.getDeviceResources()
    fun getSettingsStore() = c.settingsStore

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun searchHuggingFace(query: String, includeLiteRt: Boolean) {
        viewModelScope.launch {
            _hfBusy.value = true
            _selectedRepo.value = null
            _repoFiles.value = emptyList()
            c.modelManager.searchHuggingFace(query, includeLiteRt)
                .onSuccess { _hfResults.value = it }
                .onFailure { _statusBanner.value = it.message ?: "Hugging Face search failed" }
            _hfBusy.value = false
        }
    }

    fun openRepoFiles(repo: HfModelSummary, contextLen: Int = 2048) {
        viewModelScope.launch {
            _hfBusy.value = true
            _selectedRepo.value = repo
            c.modelManager.listRepoFiles(repo, contextLen)
                .onSuccess { _repoFiles.value = it }
                .onFailure { _statusBanner.value = it.message ?: "Failed to load repo files" }
            _hfBusy.value = false
        }
    }

    fun closeRepoFiles() {
        _selectedRepo.value = null
        _repoFiles.value = emptyList()
    }

    fun downloadCandidate(candidate: HfModelFileCandidate) {
        if (candidate.fitStatus == DeviceFitStatus.WONT_FIT) {
            _statusBanner.value = "Warning: ${candidate.fileName} is marked Won't fit (~${candidate.estRamMb} MB RAM)."
        } else {
            _statusBanner.value = "Queued background download: ${candidate.fileName}"
        }
        c.modelManager.enqueueBackgroundDownload(candidate)
    }

    fun cancelDownload() {
        c.modelManager.cancelBackgroundDownloads()
        _downloadProgress.value = null
        _statusBanner.value = "Download cancelled (partial file kept for resume)"
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            c.modelManager.importLocalUri(uri)
                .onSuccess { _statusBanner.value = "Imported ${it.name}" }
                .onFailure { _statusBanner.value = it.message ?: "Import failed" }
        }
    }

    fun deleteModel(model: ModelEntity) {
        viewModelScope.launch {
            if (activeRuntime.value.model?.id == model.id) {
                c.activeEngine().unload()
                c.updateRuntime(ActiveRuntimeState())
            }
            c.modelManager.deleteModel(model)
        }
    }

    fun loadModel(model: ModelEntity, config: InferenceConfig) {
        viewModelScope.launch {
            _statusBanner.value = "Loading ${model.name} on ${config.backend.displayName}..."
            val engine = if (model.format == ModelFormat.LITERT.name || config.backend == ComputeBackend.LITERT_GPU) {
                c.liteRtEngine
            } else {
                c.llamaEngine
            }
            engine.loadModel(model, config)
                .onSuccess { res ->
                    val sessionId = c.database.sessionDao().insert(
                        SessionEntity(
                            modelId = model.id,
                            backend = config.backend.name,
                            threads = config.threads,
                            gpuLayers = config.gpuLayers,
                            contextLen = config.contextLength,
                            loadTimeMs = res.loadTimeMs
                        )
                    )
                    c.updateRuntime(
                        ActiveRuntimeState(
                            model = model,
                            sessionId = sessionId,
                            config = config,
                            isNative = res.isNativeExecution,
                            loadTimeMs = res.loadTimeMs
                        )
                    )
                    ChipITForegroundService.start(app, "${model.name} loaded (${config.backend.displayName})")
                    _statusBanner.value = "Loaded ${model.name} in ${res.loadTimeMs} ms"
                }
                .onFailure {
                    _statusBanner.value = it.message ?: "Failed to load model"
                }
        }
    }

    fun toggleAgentMode(enabled: Boolean) {
        _agentMode.value = enabled
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        val runtime = activeRuntime.value
        val model = runtime.model
        if (model == null) {
            _statusBanner.value = "Load a model in the Models tab first."
            return
        }

        _messages.value = _messages.value + ChatMessage(isUser = true, text = trimmed)

        if (_agentMode.value) {
            generateJob = viewModelScope.launch {
                _isGenerating.value = true
                try {
                    ChipITForegroundService.start(app, "Agent running: $trimmed")
                    val res = c.agentOrchestrator.runGoal(
                        goal = trimmed,
                        sessionId = runtime.sessionId,
                        engine = c.activeEngine(),
                        config = runtime.config
                    )
                    val reply = res.getOrElse { "Agent error: ${it.message}" }
                    _messages.value = _messages.value + ChatMessage(
                        isUser = false,
                        text = reply,
                        meta = "Agent Mode"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _statusBanner.value = e.message ?: "Agent execution failed"
                } finally {
                    _isGenerating.value = false
                }
            }
            return
        }

        generateJob = viewModelScope.launch {
            _isGenerating.value = true
            val assistantMsgId = System.nanoTime()
            _messages.value = _messages.value + ChatMessage(id = assistantMsgId, isUser = false, text = "")
            try {
                val sb = StringBuilder()
                var ttft = 0L
                var prefill = 0.0
                var decode = 0.0
                var genToks = 0

                c.activeEngine().generateStream(
                    systemPrompt = "You are ChipIT, a helpful on-device AI assistant.",
                    userPrompt = trimmed,
                    config = runtime.config
                ).collect { ev ->
                    if (ev.text.isNotEmpty()) {
                        sb.append(ev.text)
                        _messages.value = _messages.value.map {
                            if (it.id == assistantMsgId) it.copy(text = sb.toString()) else it
                        }
                    }
                    if (ev.ttftMs > 0) {
                        ttft = ev.ttftMs
                        _lastTtftMs.value = ttft
                    }
                    if (ev.prefillTps > 0) prefill = ev.prefillTps
                    if (ev.decodeTps > 0) {
                        decode = ev.decodeTps
                        _liveTokensPerSec.value = decode
                    }
                    if (ev.generatedTokens > genToks) genToks = ev.generatedTokens
                }

                runtime.sessionId?.let { sid ->
                    c.metricsCollector.recordTurnSample(
                        sessionId = sid,
                        ttftMs = ttft,
                        prefillTps = prefill,
                        decodeTps = decode,
                        batteryMahDelta = genToks * 0.04
                    )
                }
                _messages.value = _messages.value.map {
                    if (it.id == assistantMsgId) {
                        it.copy(meta = "TTFT ${ttft}ms · ${"%.1f".format(decode)} tok/s")
                    } else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = e.message ?: "Generation failed"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun stopGenerationOrAgent() {
        c.activeEngine().cancelGeneration()
        c.agentOrchestrator.kill(c.activeEngine())
        generateJob?.cancel()
        _isGenerating.value = false
    }

    fun respondToAgentConfirmation(approved: Boolean) {
        c.agentOrchestrator.respondToConfirmation(approved)
    }

    fun runBenchmark(backendOverride: ComputeBackend? = null) {
        val runtime = activeRuntime.value
        val model = runtime.model
        if (model == null) {
            _statusBanner.value = "Load a model in the Models tab before running a benchmark."
            return
        }
        val cfg = if (backendOverride != null) {
            runtime.config.copy(
                backend = backendOverride,
                gpuLayers = if (backendOverride == ComputeBackend.CPU) 0 else 24
            )
        } else {
            runtime.config
        }
        viewModelScope.launch {
            c.benchmarkRunner.runStandardSuite(model, c.activeEngine(), cfg)
                .onSuccess { _statusBanner.value = "Benchmark saved (${it.backend}: ${"%.1f".format(it.avgDecodeTps)} tok/s)" }
                .onFailure { _statusBanner.value = it.message ?: "Benchmark failed" }
        }
    }

    fun exportBenchmarks(asJson: Boolean) {
        viewModelScope.launch {
            runCatching { c.benchmarkRunner.exportToFile(asJson) }
                .onSuccess { file ->
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            app,
                            "${app.packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (asJson) "application/json" else "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(shareIntent, "Share ChipIT Benchmark").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        app.startActivity(chooser)
                    }
                    _statusBanner.value = "Exported to ${file.name}"
                }
                .onFailure { _statusBanner.value = it.message ?: "Export failed" }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChipITViewModel(application) as T
        }
    }
}

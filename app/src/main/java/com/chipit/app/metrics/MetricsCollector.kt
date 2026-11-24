package com.chipit.app.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.chipit.app.data.db.MetricSampleDao
import com.chipit.app.data.db.MetricSampleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class LiveHardwareSnapshot(
    val pssRamMb: Int = 0,
    val cpuPct: Float = 0f,
    val gpuPct: Float? = null,
    val batteryTempC: Float = 0f,
    val batteryCurrentMa: Float = 0f,
    val batteryLevelPct: Int = 100,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val thermalLabel: String = "Normal"
)

class MetricsCollector(
    private val context: Context,
    private val metricSampleDao: MetricSampleDao
) {
    private val _liveSnapshot = MutableStateFlow(LiveHardwareSnapshot())
    val liveSnapshot: StateFlow<LiveHardwareSnapshot> = _liveSnapshot.asStateFlow()

    private var lastProcCpuTicks: Long = -1L
    private var lastWallMs: Long = -1L

    fun captureSnapshot(): LiveHardwareSnapshot {
        val pssMb = readPssRamMb()
        val cpuPct = readProcessCpuPct()
        val gpuPct = readGpuUtilizationPct()
        val (tempC, currentMa, levelPct) = readBatteryInfo()
        val thermal = readThermalStatus()
        val snap = LiveHardwareSnapshot(
            pssRamMb = pssMb,
            cpuPct = cpuPct,
            gpuPct = gpuPct,
            batteryTempC = tempC,
            batteryCurrentMa = currentMa,
            batteryLevelPct = levelPct,
            thermalStatus = thermal,
            thermalLabel = thermalLabel(thermal)
        )
        _liveSnapshot.value = snap
        return snap
    }

    suspend fun recordTurnSample(
        sessionId: Long,
        ttftMs: Long,
        prefillTps: Double,
        decodeTps: Double,
        batteryMahDelta: Double
    ): MetricSampleEntity {
        val snap = captureSnapshot()
        val entity = MetricSampleEntity(
            sessionId = sessionId,
            ttftMs = ttftMs,
            prefillTps = prefillTps,
            decodeTps = decodeTps,
            pssRamMb = snap.pssRamMb,
            cpuPct = snap.cpuPct,
            gpuPct = snap.gpuPct,
            batteryMah = batteryMahDelta,
            thermalStatus = snap.thermalStatus,
            tempC = snap.batteryTempC
        )
        metricSampleDao.insert(entity)
        return entity
    }

    private fun readPssRamMb(): Int {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return (memInfo.totalPss / 1024).coerceAtLeast(1)
    }

    private fun readProcessCpuPct(): Float {
        return runCatching {
            val stat = File("/proc/self/stat").readText()
            val afterComm = stat.substringAfterLast(") ").split(" ")
            // utime (11) + stime (12) in clock ticks (typically 100 Hz)
            val utime = afterComm.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = afterComm.getOrNull(12)?.toLongOrNull() ?: 0L
            val totalTicks = utime + stime
            val nowMs = SystemClock.elapsedRealtime()

            val pct = if (lastProcCpuTicks >= 0 && lastWallMs > 0 && nowMs > lastWallMs) {
                val deltaTicks = (totalTicks - lastProcCpuTicks).coerceAtLeast(0L)
                val deltaSec = (nowMs - lastWallMs) / 1000.0
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                ((deltaTicks / 100.0) / (deltaSec * cores) * 100.0).toFloat().coerceIn(0f, 100f)
            } else {
                0f
            }
            lastProcCpuTicks = totalTicks
            lastWallMs = nowMs
            pct
        }.getOrDefault(0f)
    }

    private fun readGpuUtilizationPct(): Float? {
        // Adreno KGSL sysfs counter (readable on some devices without root)
        runCatching {
            val kgsl = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (kgsl.exists() && kgsl.canRead()) {
                val parts = kgsl.readText().trim().split(Regex("\\s+"))
                if (parts.size == 2) {
                    val busy = parts[0].toFloatOrNull() ?: 0f
                    val total = parts[1].toFloatOrNull() ?: 0f
                    if (total > 0f) return ((busy / total) * 100f).coerceIn(0f, 100f)
                }
            }
        }
        return null
    }

    private fun readBatteryInfo(): Triple<Float, Float, Int> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = kotlin.math.abs(currentUa) / 1000f
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val tempC = tenthsC / 10f
        return Triple(tempC, currentMa, level)
    }

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.currentThermalStatus
        }
        return PowerManager.THERMAL_STATUS_NONE
    }

    fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Normal"
    }
}

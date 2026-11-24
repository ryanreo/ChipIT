package com.chipit.app.model

enum class DeviceFitStatus(val label: String) {
    FITS("Fits"),
    TIGHT("Tight"),
    WONT_FIT("Won't fit")
}

data class DeviceResources(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val freeStorageMb: Long
)

object DeviceFitCalculator {
    /**
     * Estimates total runtime RAM in MB: model file weight + KV cache for context + runtime overhead.
     */
    fun estimateRuntimeRamMb(
        fileSizeBytes: Long,
        contextLength: Int = 2048,
        paramCountB: Double = 0.0
    ): Int {
        val weightsMb = (fileSizeBytes / (1024.0 * 1024.0)).toInt()
        val effectiveParamsB = if (paramCountB > 0.0) paramCountB else (weightsMb / 650.0).coerceAtLeast(0.5)
        // Approximate KV cache (~128 MB per 2048 ctx per ~3B params in FP16)
        val kvCacheMb = ((contextLength / 2048.0) * (effectiveParamsB / 3.0) * 128.0).toInt().coerceAtLeast(64)
        val runtimeOverheadMb = 320
        return weightsMb + kvCacheMb + runtimeOverheadMb
    }

    fun evaluateFit(
        fileSizeBytes: Long,
        estRamMb: Int,
        device: DeviceResources
    ): DeviceFitStatus {
        val fileSizeMb = fileSizeBytes / (1024L * 1024L)
        if (fileSizeMb > device.freeStorageMb) return DeviceFitStatus.WONT_FIT
        if (device.totalRamMb <= 0) return DeviceFitStatus.TIGHT

        val ratio = estRamMb.toDouble() / device.totalRamMb.toDouble()
        return when {
            ratio <= 0.60 -> DeviceFitStatus.FITS
            ratio <= 0.80 -> DeviceFitStatus.TIGHT
            else -> DeviceFitStatus.WONT_FIT
        }
    }
}

package com.chipit.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ModelFormat {
    GGUF,
    LITERT
}

enum class ComputeBackend(val displayName: String) {
    CPU("CPU (ARM NEON)"),
    VULKAN("GPU (Vulkan)"),
    OPENCL("GPU (OpenCL Adreno)"),
    HYBRID("Hybrid (CPU + GPU)"),
    LITERT_GPU("LiteRT-LM GPU")
}

enum class AgentTier {
    INTENT,
    APP_FUNCTION,
    ACCESSIBILITY
}

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val repoId: String,
    val fileName: String,
    val sourceUrl: String,
    val localPath: String,
    val format: String, // ModelFormat.name
    val quantization: String,
    val paramCountB: Double,
    val sizeBytes: Long,
    val sha256: String?,
    val estRamMb: Int,
    val license: String,
    val contextLimit: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("modelId")]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: Long,
    val backend: String, // ComputeBackend.name
    val threads: Int,
    val gpuLayers: Int,
    val contextLen: Int,
    val loadTimeMs: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null
)

@Entity(
    tableName = "metric_samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MetricSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val ttftMs: Long,
    val prefillTps: Double,
    val decodeTps: Double,
    val pssRamMb: Int,
    val cpuPct: Float,
    val gpuPct: Float?,
    val batteryMah: Double,
    val thermalStatus: Int,
    val tempC: Float
)

@Entity(
    tableName = "benchmark_runs",
    foreignKeys = [
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("modelId")]
)
data class BenchmarkRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: Long,
    val modelName: String,
    val suiteName: String,
    val backend: String,
    val configJson: String,
    val avgTtftMs: Long,
    val avgPrefillTps: Double,
    val avgDecodeTps: Double,
    val peakRamMb: Int,
    val batteryPctPer1kTok: Double,
    val maxThermalStatus: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_action_logs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("sessionId")]
)
data class AgentActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val stepIndex: Int,
    val targetPackage: String,
    val tier: String, // AgentTier.name
    val toolJson: String,
    val requiresConfirm: Boolean,
    val userApproved: Boolean?,
    val status: String,
    val errorMsg: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

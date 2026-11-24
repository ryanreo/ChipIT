package com.chipit.app.engine

import com.chipit.app.data.db.ComputeBackend
import com.chipit.app.data.db.ModelEntity
import kotlinx.coroutines.flow.Flow

data class InferenceConfig(
    val backend: ComputeBackend = ComputeBackend.CPU,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val contextLength: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 512
)

data class LoadResult(
    val loadTimeMs: Long,
    val isNativeExecution: Boolean,
    val backendLabel: String
)

data class TokenEvent(
    val text: String,
    val isFinal: Boolean = false,
    val ttftMs: Long = 0L,
    val prefillTps: Double = 0.0,
    val decodeTps: Double = 0.0,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0
)

data class BackendCapabilities(
    val supportsCpuNeon: Boolean,
    val supportsVulkan: Boolean,
    val supportsOpenCl: Boolean,
    val supportsLiteRtGpu: Boolean,
    val isNativeLlamaLoaded: Boolean
)

interface InferenceEngine {
    val engineName: String
    val isLoaded: Boolean

    suspend fun loadModel(model: ModelEntity, config: InferenceConfig): Result<LoadResult>
    fun generateStream(systemPrompt: String, userPrompt: String, config: InferenceConfig): Flow<TokenEvent>
    fun cancelGeneration()
    suspend fun unload()
}

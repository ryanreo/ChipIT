package com.chipit.app.engine

object LlamaJni {
    val isNativeAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("chipit_llama")
            true
        }.getOrElse { false }
    }

    external fun nativeLoadModel(
        modelPath: String,
        threads: Int,
        gpuLayers: Int,
        contextLen: Int,
        useVulkan: Boolean,
        useOpenCl: Boolean
    ): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenCallback
    )

    external fun nativeCancel(handle: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeSupportsVulkan(): Boolean

    interface TokenCallback {
        fun onToken(token: String)
        fun onMetrics(ttftMs: Long, prefillTps: Double, decodeTps: Double, promptTokens: Int, genTokens: Int)
    }
}

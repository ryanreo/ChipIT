package com.chipit.app

import android.content.Context
import com.chipit.app.agent.AgentOrchestrator
import com.chipit.app.agent.ToolRegistry
import com.chipit.app.data.db.ChipITDatabase
import com.chipit.app.data.db.ComputeBackend
import com.chipit.app.data.db.ModelEntity
import com.chipit.app.data.db.ModelFormat
import com.chipit.app.data.prefs.SettingsStore
import com.chipit.app.engine.InferenceConfig
import com.chipit.app.engine.InferenceEngine
import com.chipit.app.engine.LiteRtEngine
import com.chipit.app.engine.LlamaCppEngine
import com.chipit.app.metrics.BenchmarkRunner
import com.chipit.app.metrics.MetricsCollector
import com.chipit.app.model.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveRuntimeState(
    val model: ModelEntity? = null,
    val sessionId: Long? = null,
    val config: InferenceConfig = InferenceConfig(),
    val isNative: Boolean = false,
    val loadTimeMs: Long = 0L
)

class AppContainer(context: Context) {
    val database = ChipITDatabase.create(context)
    val settingsStore = SettingsStore(context)
    val modelManager = ModelManager(context, database.modelDao(), settingsStore)
    val llamaEngine = LlamaCppEngine()
    val liteRtEngine = LiteRtEngine()
    val metricsCollector = MetricsCollector(context, database.metricSampleDao())
    val benchmarkRunner = BenchmarkRunner(
        context = context,
        benchmarkRunDao = database.benchmarkRunDao(),
        sessionDao = database.sessionDao(),
        metricsCollector = metricsCollector
    )
    val toolRegistry = ToolRegistry(context, settingsStore)
    val agentOrchestrator = AgentOrchestrator(
        toolRegistry = toolRegistry,
        actionLogDao = database.agentActionLogDao(),
        settingsStore = settingsStore
    )

    private val _activeRuntime = MutableStateFlow(ActiveRuntimeState())
    val activeRuntime: StateFlow<ActiveRuntimeState> = _activeRuntime.asStateFlow()

    fun updateRuntime(state: ActiveRuntimeState) {
        _activeRuntime.value = state
    }

    fun activeEngine(): InferenceEngine {
        val model = _activeRuntime.value.model
        return if (model?.format == ModelFormat.LITERT.name ||
            _activeRuntime.value.config.backend == ComputeBackend.LITERT_GPU
        ) {
            liteRtEngine
        } else {
            llamaEngine
        }
    }
}

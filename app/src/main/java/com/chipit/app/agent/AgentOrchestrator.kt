package com.chipit.app.agent

import com.chipit.app.agent.accessibility.AccessibilityBridge
import com.chipit.app.data.db.AgentActionLogDao
import com.chipit.app.data.db.AgentActionLogEntity
import com.chipit.app.data.db.AgentTier
import com.chipit.app.data.prefs.SettingsStore
import com.chipit.app.engine.InferenceConfig
import com.chipit.app.engine.InferenceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

class AgentOrchestrator(
    private val toolRegistry: ToolRegistry,
    private val actionLogDao: AgentActionLogDao,
    private val settingsStore: SettingsStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val killRequested = AtomicBoolean(false)
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    fun observeActionLogs(): Flow<List<AgentActionLogEntity>> = actionLogDao.observeRecent()

    fun respondToConfirmation(approved: Boolean) {
        confirmationDeferred?.complete(approved)
        _state.value = _state.value.copy(pendingConfirmation = null)
    }

    fun kill(engine: InferenceEngine?) {
        killRequested.set(true)
        confirmationDeferred?.complete(false)
        engine?.cancelGeneration()
        _state.value = _state.value.copy(
            isRunning = false,
            pendingConfirmation = null,
            statusMessage = "Stopped by Kill Switch",
            lastResult = "Agent stopped by user."
        )
    }

    suspend fun runGoal(
        goal: String,
        sessionId: Long?,
        engine: InferenceEngine,
        config: InferenceConfig
    ): Result<String> = runCatching {
        if (!engine.isLoaded) error("Load a model before running Agent Mode.")
        killRequested.set(false)
        val maxSteps = settingsStore.agentMaxSteps
        val history = mutableListOf<String>()
        var lastScreenHash = 0
        var repeatedScreenCount = 0

        _state.value = AgentUiState(
            isRunning = true,
            currentStep = 0,
            maxSteps = maxSteps,
            statusMessage = "Starting agent..."
        )

        for (step in 1..maxSteps) {
            if (killRequested.get()) {
                return@runCatching "Agent killed."
            }

            // 1. Observe
            val obs = AccessibilityBridge.captureScreen()
            if (obs != null && obs.screenHash != 0) {
                if (obs.screenHash == lastScreenHash) repeatedScreenCount++ else repeatedScreenCount = 0
                lastScreenHash = obs.screenHash
                if (repeatedScreenCount >= 2 && step > 2) {
                    val msg = "Stopped: screen unchanged after 2 consecutive actions."
                    _state.value = _state.value.copy(isRunning = false, statusMessage = msg, lastResult = msg)
                    return@runCatching msg
                }
            }

            _state.value = _state.value.copy(
                currentStep = step,
                statusMessage = "Step $step/$maxSteps: Thinking..."
            )

            // 2. Think
            val prompt = buildAgentPrompt(goal, obs, history)
            var rawReply = collectReply(engine, prompt, config)
            var toolCall = parseToolCall(rawReply)

            if (toolCall == null && !killRequested.get()) {
                // Retry once on malformed JSON
                rawReply = collectReply(
                    engine,
                    """Previous output was invalid JSON. Respond ONLY with one JSON object like {"tool":"open_app","args":{"package_name":"com.spotify.music"}}.""",
                    config
                )
                toolCall = parseToolCall(rawReply)
            }

            if (toolCall == null) {
                val err = "Malformed tool JSON from model: ${rawReply.take(120)}"
                actionLogDao.insert(
                    AgentActionLogEntity(
                        sessionId = sessionId,
                        stepIndex = step,
                        targetPackage = obs?.packageName ?: "unknown",
                        tier = AgentTier.INTENT.name,
                        toolJson = rawReply.take(300),
                        requiresConfirm = false,
                        userApproved = null,
                        status = "FAILED",
                        errorMsg = err
                    )
                )
                _state.value = _state.value.copy(isRunning = false, statusMessage = err, lastResult = err)
                return@runCatching err
            }

            if (toolCall.tool.equals("done", ignoreCase = true)) {
                val summary = toolCall.argString("summary") ?: "Task completed."
                actionLogDao.insert(
                    AgentActionLogEntity(
                        sessionId = sessionId,
                        stepIndex = step,
                        targetPackage = obs?.packageName ?: "chipit",
                        tier = AgentTier.INTENT.name,
                        toolJson = rawReply,
                        requiresConfirm = false,
                        userApproved = null,
                        status = "DONE"
                    )
                )
                _state.value = _state.value.copy(isRunning = false, statusMessage = "Done", lastResult = summary)
                return@runCatching summary
            }

            if (toolCall.tool.equals("ask_user", ignoreCase = true)) {
                val q = toolCall.argString("message") ?: "Need your input to proceed."
                _state.value = _state.value.copy(isRunning = false, statusMessage = q, lastResult = q)
                return@runCatching q
            }

            // 3. Risk gate
            val nodeId = toolCall.argInt("node_id")
            val nodeText = obs?.nodes?.firstOrNull { it.nodeId == nodeId }?.text
            val needsConfirm = RiskyActionClassifier.isRisky(toolCall, nodeText)
            var approved: Boolean? = null

            if (needsConfirm) {
                val deferred = CompletableDeferred<Boolean>()
                confirmationDeferred = deferred
                _state.value = _state.value.copy(
                    statusMessage = "Waiting for confirmation...",
                    pendingConfirmation = AgentConfirmationRequest(
                        stepIndex = step,
                        targetPackage = obs?.packageName ?: toolCall.argString("package_name") ?: "app",
                        toolName = toolCall.tool,
                        summary = "Allow ${toolCall.tool} (${toolCall.args})?",
                        rawJson = rawReply
                    )
                )
                approved = deferred.await()
                confirmationDeferred = null
                if (!approved || killRequested.get()) {
                    actionLogDao.insert(
                        AgentActionLogEntity(
                            sessionId = sessionId,
                            stepIndex = step,
                            targetPackage = obs?.packageName ?: "unknown",
                            tier = AgentTier.ACCESSIBILITY.name,
                            toolJson = rawReply,
                            requiresConfirm = true,
                            userApproved = false,
                            status = "DENIED"
                        )
                    )
                    val msg = "Action declined by user."
                    _state.value = _state.value.copy(isRunning = false, statusMessage = msg, lastResult = msg)
                    return@runCatching msg
                }
            }

            // 4. Act & Verify
            _state.value = _state.value.copy(statusMessage = "Step $step/$maxSteps: Executing ${toolCall.tool}...")
            val exec = toolRegistry.execute(toolCall, obs)
            actionLogDao.insert(
                AgentActionLogEntity(
                    sessionId = sessionId,
                    stepIndex = step,
                    targetPackage = exec.targetPackage,
                    tier = exec.tier.name,
                    toolJson = rawReply,
                    requiresConfirm = needsConfirm,
                    userApproved = approved,
                    status = if (exec.success) "SUCCESS" else "FAILED",
                    errorMsg = if (exec.success) null else exec.message
                )
            )
            history += "Step $step: ${toolCall.tool} -> ${exec.message}"
            delay(850) // Allow UI to settle before next observation
        }

        val limitMsg = "Reached max steps ($maxSteps)."
        _state.value = _state.value.copy(isRunning = false, statusMessage = limitMsg, lastResult = limitMsg)
        limitMsg
    }.also {
        if (it.isFailure) {
            _state.value = _state.value.copy(
                isRunning = false,
                statusMessage = "Error: ${it.exceptionOrNull()?.message}",
                lastResult = it.exceptionOrNull()?.message
            )
        }
    }

    private suspend fun collectReply(
        engine: InferenceEngine,
        prompt: String,
        config: InferenceConfig
    ): String {
        val sb = StringBuilder()
        engine.generateStream(
            systemPrompt = "You are ChipIT's Android automation agent. Output ONLY valid JSON tool calls.",
            userPrompt = prompt,
            config = config.copy(temperature = 0.1f, maxTokens = 160)
        ).collect { ev ->
            sb.append(ev.text)
        }
        return sb.toString().trim()
    }

    private fun parseToolCall(raw: String): ToolCall? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val slice = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString<ToolCall>(slice) }.getOrNull()
    }

    private fun buildAgentPrompt(
        goal: String,
        obs: ScreenObservation?,
        history: List<String>
    ): String = buildString {
        appendLine("Goal: $goal")
        appendLine("Available tools: open_app(package_name), launch_intent(action,uri), call_app_function(package_name,function_id), tap(node_id), type_text(node_id,text), scroll(direction), navigate_back(), ask_user(message), done(summary)")
        if (history.isNotEmpty()) {
            appendLine("History:")
            history.takeLast(5).forEach { appendLine("- $it") }
        }
        if (obs != null) {
            appendLine("Current screen:")
            appendLine(obs.treeSummary)
        }
        appendLine("Respond with ONE JSON object:")
    }
}

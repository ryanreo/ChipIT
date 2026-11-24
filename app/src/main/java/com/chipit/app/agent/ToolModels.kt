package com.chipit.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap())
) {
    fun argString(key: String): String? = args[key]?.jsonPrimitive?.content
    fun argInt(key: String): Int? = args[key]?.jsonPrimitive?.content?.toIntOrNull()
}

data class UiTreeNode(
    val nodeId: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean
)

data class ScreenObservation(
    val packageName: String,
    val nodes: List<UiTreeNode>,
    val treeSummary: String,
    val screenHash: Int
)

data class AgentConfirmationRequest(
    val stepIndex: Int,
    val targetPackage: String,
    val toolName: String,
    val summary: String,
    val rawJson: String
)

data class AgentUiState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val maxSteps: Int = 12,
    val statusMessage: String = "Idle",
    val pendingConfirmation: AgentConfirmationRequest? = null,
    val lastResult: String? = null
)

object RiskyActionClassifier {
    private val riskyKeywords = listOf(
        "send", "submit", "pay", "checkout", "purchase", "buy", "order",
        "transfer", "delete", "remove", "trash", "erase", "uninstall", "confirm"
    )

    fun isRisky(toolCall: ToolCall, targetNodeText: String?): Boolean {
        val tool = toolCall.tool.lowercase()
        if (tool == "launch_intent") {
            val action = toolCall.argString("action")?.lowercase().orEmpty()
            if (action.contains("sendto") || action.contains("send")) return true
        }
        val combined = buildString {
            append(tool).append(' ')
            append(toolCall.args.toString().lowercase()).append(' ')
            append(targetNodeText?.lowercase().orEmpty())
        }
        return riskyKeywords.any { combined.contains(it) }
    }
}

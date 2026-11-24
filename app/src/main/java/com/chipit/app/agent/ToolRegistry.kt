package com.chipit.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.chipit.app.agent.accessibility.AccessibilityBridge
import com.chipit.app.data.db.AgentTier
import com.chipit.app.data.prefs.SettingsStore

data class ToolExecutionResult(
    val tier: AgentTier,
    val targetPackage: String,
    val success: Boolean,
    val message: String
)

class ToolRegistry(
    private val context: Context,
    private val settingsStore: SettingsStore
) {
    fun execute(call: ToolCall, currentObservation: ScreenObservation?): ToolExecutionResult {
        return when (call.tool.lowercase()) {
            // Tier 1: Intents & Deep Links
            "open_app" -> {
                val pkg = call.argString("package_name").orEmpty()
                if (pkg.isBlank()) {
                    return ToolExecutionResult(AgentTier.INTENT, "unknown", false, "Missing package_name")
                }
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return ToolExecutionResult(AgentTier.INTENT, pkg, false, "App not installed: $pkg")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ToolExecutionResult(AgentTier.INTENT, pkg, true, "Launched $pkg")
            }

            "launch_intent" -> {
                val action = call.argString("action") ?: Intent.ACTION_VIEW
                val uriStr = call.argString("uri")
                val intent = Intent(action).apply {
                    if (!uriStr.isNullOrBlank()) data = Uri.parse(uriStr)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = intent.resolveActivity(context.packageManager)
                    ?: return ToolExecutionResult(AgentTier.INTENT, "system", false, "No app handles intent $action")
                context.startActivity(intent)
                ToolExecutionResult(AgentTier.INTENT, resolved.packageName, true, "Triggered intent $action")
            }

            // Tier 2: Android AppFunctions
            "call_app_function" -> {
                val pkg = call.argString("package_name") ?: "unknown"
                val fnId = call.argString("function_id") ?: ""
                if (Build.VERSION.SDK_INT < 36) {
                    ToolExecutionResult(
                        AgentTier.APP_FUNCTION,
                        pkg,
                        false,
                        "AppFunctions API requires Android 16+; falling back to Intents/Accessibility for $fnId"
                    )
                } else {
                    ToolExecutionResult(
                        AgentTier.APP_FUNCTION,
                        pkg,
                        false,
                        "Target app $pkg does not expose AppFunction '$fnId'; falling back to Accessibility"
                    )
                }
            }

            // Tier 3: AccessibilityService
            "tap", "type_text", "scroll", "navigate_back" -> {
                if (!AccessibilityBridge.isConnected.value) {
                    return ToolExecutionResult(
                        AgentTier.ACCESSIBILITY,
                        currentObservation?.packageName ?: "unknown",
                        false,
                        "ChipIT AccessibilityService is not enabled"
                    )
                }
                val pkg = currentObservation?.packageName ?: "unknown"
                if (pkg != "unknown" && pkg != context.packageName && !settingsStore.isPackageAllowed(pkg)) {
                    return ToolExecutionResult(
                        AgentTier.ACCESSIBILITY,
                        pkg,
                        false,
                        "Blocked by per-app allow-list: $pkg"
                    )
                }
                val ok = when (call.tool.lowercase()) {
                    "tap" -> AccessibilityBridge.performTap(call.argInt("node_id") ?: -1)
                    "type_text" -> AccessibilityBridge.performTypeText(
                        call.argInt("node_id") ?: -1,
                        call.argString("text").orEmpty()
                    )
                    "scroll" -> AccessibilityBridge.performScroll(call.argString("direction") ?: "down")
                    "navigate_back" -> AccessibilityBridge.performBack()
                    else -> false
                }
                ToolExecutionResult(
                    AgentTier.ACCESSIBILITY,
                    pkg,
                    ok,
                    if (ok) "Executed ${call.tool}" else "Failed to execute ${call.tool}"
                )
            }

            else -> ToolExecutionResult(
                AgentTier.INTENT,
                currentObservation?.packageName ?: "unknown",
                false,
                "Unknown tool '${call.tool}'"
            )
        }
    }
}

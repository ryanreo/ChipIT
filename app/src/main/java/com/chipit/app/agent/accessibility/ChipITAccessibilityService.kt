package com.chipit.app.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.chipit.app.agent.ScreenObservation
import com.chipit.app.agent.UiTreeNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object AccessibilityBridge {
    private var serviceRef: WeakReference<ChipITAccessibilityService>? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun attach(service: ChipITAccessibilityService) {
        serviceRef = WeakReference(service)
        _isConnected.value = true
    }

    fun detach() {
        serviceRef = null
        _isConnected.value = false
    }

    fun captureScreen(): ScreenObservation? = serviceRef?.get()?.capturePrunedScreen()
    fun performTap(nodeId: Int): Boolean = serviceRef?.get()?.tapNode(nodeId) ?: false
    fun performTypeText(nodeId: Int, text: String): Boolean = serviceRef?.get()?.typeTextNode(nodeId, text) ?: false
    fun performScroll(direction: String): Boolean = serviceRef?.get()?.scrollScreen(direction) ?: false
    fun performBack(): Boolean = serviceRef?.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
}

class ChipITAccessibilityService : AccessibilityService() {

    private val nodeMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen observation is pulled on-demand by AgentOrchestrator
    }

    override fun onInterrupt() {
        nodeMap.clear()
    }

    override fun onDestroy() {
        nodeMap.clear()
        AccessibilityBridge.detach()
        super.onDestroy()
    }

    fun capturePrunedScreen(): ScreenObservation = runCatching {
        nodeMap.clear()
        val root = rootInActiveWindow ?: return@runCatching ScreenObservation(
            packageName = "unknown",
            nodes = emptyList(),
            treeSummary = "(No active window)",
            screenHash = 0
        )
        val pkg = root.packageName?.toString() ?: "unknown"
        val collected = mutableListOf<UiTreeNode>()
        var nextId = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || collected.size >= 80) return
            // Security: redact password / sensitive fields before LLM sees UI tree
            if (node.isPassword) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interactive = node.isClickable || node.isEditable || node.isScrollable

            if (interactive || text.isNotEmpty() || desc.isNotEmpty()) {
                val id = nextId++
                nodeMap[id] = node
                collected += UiTreeNode(
                    nodeId = id,
                    className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                    text = text.take(80),
                    contentDesc = desc.take(80),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable
                )
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        val summary = buildString {
            appendLine("Package: $pkg")
            for (n in collected) {
                val label = n.text.ifEmpty { n.contentDesc }.ifEmpty { n.className }
                val flags = buildList {
                    if (n.clickable) add("tap")
                    if (n.editable) add("edit")
                    if (n.scrollable) add("scroll")
                }.joinToString(",")
                appendLine("[node ${n.nodeId}: \"$label\" ($flags)]")
            }
        }
        ScreenObservation(
            packageName = pkg,
            nodes = collected,
            treeSummary = summary,
            screenHash = summary.hashCode()
        )
    }.getOrDefault(
        ScreenObservation(
            packageName = "unknown",
            nodes = emptyList(),
            treeSummary = "(Unable to read window)",
            screenHash = 0
        )
    )

    fun tapNode(nodeId: Int): Boolean = runCatching {
        val node = nodeMap[nodeId] ?: return@runCatching false
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) {
                return@runCatching target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.getOrDefault(false)

    fun typeTextNode(nodeId: Int, text: String): Boolean = runCatching {
        val node = nodeMap[nodeId] ?: return@runCatching false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    fun scrollScreen(direction: String): Boolean = runCatching {
        val action = if (direction.lowercase() in listOf("up", "backward", "left")) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val scrollable = nodeMap.values.firstOrNull { it.isScrollable } ?: rootInActiveWindow ?: return@runCatching false
        scrollable.performAction(action)
    }.getOrDefault(false)
}

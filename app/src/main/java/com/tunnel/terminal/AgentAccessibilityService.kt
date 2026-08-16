package com.tunnel.terminal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

/**
 * AgentAccessibilityService — Screen reading + UI automation via AccessibilityService.
 *
 * Ported from private-agent (github.com/orailnoor/private-agent) v1.0.2.
 *
 * Phase 1 Integration (v9.5.0):
 * - Direct lift from private-agent's AgentAccessibilityService.kt (516 lines)
 * - Package changed: com.orailnoor.privateagent → com.tunnel.terminal
 * - ownPackageName updated to com.tunnel.terminal
 * - Security hardening: config uses specific event types (not typeAllMask)
 * - All AccessibilityNodeInfo.recycle() calls preserved (memory-safe)
 *
 * Capabilities:
 * - dumpScreen(): List<Map> — flat UI tree with text, bounds, clickability
 * - clickByText(targetText): Boolean — 4-strategy match (exact/contains × skip/allow editable)
 * - clickAtCoordinates(x, y): Boolean — gesture-based tap
 * - typeText(text, fieldHint): Boolean — ACTION_FOCUS + ACTION_SET_TEXT
 * - pressEnter(): Boolean — IME enter / keyboard action / IME window tap
 * - scroll(direction): Boolean — ACTION_SCROLL_FORWARD/BACKWARD
 * - swipe(startX, startY, endX, endY): Boolean — gesture stroke
 * - longPressAt(x, y): Boolean — 1000ms gesture
 * - pressBack/pressHome/openRecents/openNotifications: Boolean — global actions
 * - getCurrentPackage(): String? — active app package name
 * - takeScreenshot(callback): Base64 JPEG (API 30+)
 *
 * Communication: Static singleton pattern (instance + eventListener).
 * No MethodChannel needed — Tunnel Terminal is native Kotlin.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val ownPackageName = "com.tunnel.terminal"

    companion object {
        private const val TAG = "AgentA11y"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /** Set by AgentActionExecutor to receive live UI events (click/scroll). */
        @Volatile
        var eventListener: ((Map<String, Any>) -> Unit)? = null

        /** Check if accessibility service is enabled and running. */
        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val listener = eventListener ?: return

        // Filter out events from our own app
        if (event.packageName?.toString() == ownPackageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                var text = node?.text?.toString() ?: node?.contentDescription?.toString() ?: ""
                if (text.isEmpty() && event.text.isNotEmpty()) {
                    text = event.text.joinToString(" ")
                }

                val rect = Rect()
                node?.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()

                if (text.isNotEmpty() || (cx != 0 || cy != 0)) {
                    listener(mapOf(
                        "type" to "click",
                        "text" to text,
                        "x" to cx,
                        "y" to cy
                    ))
                }
                node?.recycle()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                listener(mapOf("type" to "scroll"))
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AgentAccessibilityService destroyed")
    }

    // ─── Screen Reading ──────────────────────────────────────────

    /**
     * Dump the current screen as a flat list of UI elements.
     * Each element is a Map with: index, text, contentDescription, className,
     * viewId, isClickable, isEditable, isScrollable, isCheckable, isChecked,
     * isFocused, bounds {left, top, right, bottom}, depth.
     *
     * Filters out:
     * - Invisible/zero-size nodes (but still traverses their children)
     * - Nodes with no text/desc and not interactive
     * - Windows from our own app
     */
    fun dumpScreen(): List<Map<String, Any?>> {
        val nodes = mutableListOf<Map<String, Any?>>()
        val allWindows = windows
        if (allWindows == null || allWindows.isEmpty()) {
            val root = rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != ownPackageName) {
                traverseNode(root, nodes, 0)
            }
            root.recycle()
            return nodes
        }

        for (window in allWindows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) {
                root.recycle()
                continue
            }
            traverseNode(root, nodes, 0)
            root.recycle()
        }
        return nodes
    }

    /**
     * Get a compressed text description of the screen for AI context.
     * Returns a formatted string with indexed elements + center coordinates.
     */
    fun getScreenDescription(): String {
        val nodes = dumpScreen()
        if (nodes.isEmpty()) return "(empty screen)"

        val sb = StringBuilder()
        for (node in nodes) {
            val index = node["index"] ?: continue
            val text = node["text"] as? String ?: ""
            val desc = node["contentDescription"] as? String ?: ""
            val className = node["className"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val bounds = node["bounds"] as? Map<String, Int> ?: continue
            val cx = (bounds["left"]!! + bounds["right"]!!) / 2
            val cy = (bounds["top"]!! + bounds["bottom"]!!) / 2
            val isClickable = node["isClickable"] as? Boolean ?: false
            val isEditable = node["isEditable"] as? Boolean ?: false
            val isScrollable = node["isScrollable"] as? Boolean ?: false

            val displayText = if (text.isNotEmpty()) text else desc
            val attrs = mutableListOf<String>()
            if (isClickable) attrs.add("clickable")
            if (isEditable) attrs.add("editable")
            if (isScrollable) attrs.add("scrollable")

            sb.append("[$index] $displayText")
            if (attrs.isNotEmpty()) sb.append(" (${attrs.joinToString(", ")})")
            sb.append(" @($cx,$cy)\n")
        }
        return sb.toString().trim()
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        nodes: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val isZeroSize = rect.width() <= 0 || rect.height() <= 0
        if (!node.isVisibleToUser || isZeroSize) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNode(child, nodes, depth + 1)
                child.recycle()
            }
            return
        }

        if (text.isNotEmpty() || contentDesc.isNotEmpty() ||
            node.isClickable || node.isEditable || node.isScrollable
        ) {
            nodes.add(mapOf(
                "index" to nodes.size,
                "text" to text,
                "contentDescription" to contentDesc,
                "className" to className.substringAfterLast('.'),
                "viewId" to viewId,
                "isClickable" to node.isClickable,
                "isEditable" to node.isEditable,
                "isScrollable" to node.isScrollable,
                "isCheckable" to node.isCheckable,
                "isChecked" to node.isChecked,
                "isFocused" to node.isFocused,
                "bounds" to mapOf(
                    "left" to rect.left,
                    "top" to rect.top,
                    "right" to rect.right,
                    "bottom" to rect.bottom
                ),
                "depth" to depth
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, nodes, depth + 1)
            child.recycle()
        }
    }

    // ─── Actions ─────────────────────────────────────────────────

    /** Find and click a node by its text content. Uses 4-strategy match. */
    fun clickByText(targetText: String): Boolean {
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) {
                root.recycle()
                continue
            }
            val result = findAndClickNode(root, targetText, true, true)
                || findAndClickNode(root, targetText, false, true)
                || findAndClickNode(root, targetText, true, false)
                || findAndClickNode(root, targetText, false, false)
            root.recycle()
            if (result) return true
        }
        return false
    }

    private fun findAndClickNode(
        node: AccessibilityNodeInfo,
        targetText: String,
        exactOnly: Boolean,
        skipEditable: Boolean
    ): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        val exactMatch = text.equals(targetText, ignoreCase = true)
            || desc.equals(targetText, ignoreCase = true)
        val containsMatch = text.contains(targetText, ignoreCase = true)
            || desc.contains(targetText, ignoreCase = true)
        val matches = if (exactOnly) exactMatch else containsMatch

        if (matches && (!skipEditable || !node.isEditable) && clickNodeOrParent(node)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickNode(child, targetText, exactOnly, skipEditable)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var clickTarget: AccessibilityNodeInfo? = node
        while (clickTarget != null && !clickTarget.isClickable) {
            clickTarget = clickTarget.parent
        }
        if (clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            return true
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        return !rect.isEmpty && clickAtCoordinates(
            rect.centerX().toFloat(),
            rect.centerY().toFloat()
        )
    }

    /** Click at specific coordinates using gesture. */
    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Type text into the first (or hinted) editable field. */
    fun typeText(text: String, fieldHint: String? = null): Boolean {
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) {
                root.recycle()
                continue
            }

            var editNode = findEditableNode(root, fieldHint)
            if (editNode == null && !fieldHint.isNullOrEmpty()) {
                editNode = findEditableNode(root, null)
            }

            if (editNode != null) {
                editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                val success = editNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )
                root.recycle()
                return success
            }
            root.recycle()
        }
        return false
    }

    /** Submit the focused field through the IME, with keyboard-aware fallbacks. */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == ownPackageName) {
                    root.recycle()
                    continue
                }
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val submitted = focused?.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                ) == true
                focused?.recycle()
                root.recycle()
                if (submitted) return true
            }
        }

        for (window in windows) {
            val root = window.root ?: continue
            val actionNode = findKeyboardActionNode(root)
            val submitted = actionNode != null && clickNodeOrParent(actionNode)
            actionNode?.recycle()
            root.recycle()
            if (submitted) return true
        }

        // Tap inside the actual IME window
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val x = bounds.right - (bounds.width() * 0.10f)
                val y = bounds.bottom - (bounds.height() * 0.14f)
                return clickAtCoordinates(x, y)
            }
        }
        return false
    }

    private fun findKeyboardActionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (node.text?.toString().orEmpty().ifEmpty {
            node.contentDescription?.toString().orEmpty()
        }).trim().lowercase()
        val actionLabels = setOf("search", "enter", "go", "done", "send", "next")
        if (node.isClickable && (label in actionLabels || label.endsWith(" search"))) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findKeyboardActionNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNode(
        node: AccessibilityNodeInfo,
        hint: String?
    ): AccessibilityNodeInfo? {
        if (node.isEditable) {
            if (hint == null) return node
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val hintText = node.hintText?.toString() ?: ""
            if (text.contains(hint, ignoreCase = true) ||
                desc.contains(hint, ignoreCase = true) ||
                hintText.contains(hint, ignoreCase = true)
            ) {
                return node
            }
            if (hint.isNullOrEmpty()) return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child, hint)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /** Scroll forward/backward on the first scrollable element. */
    fun scroll(direction: String, targetText: String? = null): Boolean {
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) {
                root.recycle()
                continue
            }
            val scrollNode = findScrollableNode(root, targetText)
            if (scrollNode != null) {
                val action = when (direction.lowercase()) {
                    "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val success = scrollNode.performAction(action)
                root.recycle()
                return success
            }
            root.recycle()
        }
        return false
    }

    private fun findScrollableNode(
        node: AccessibilityNodeInfo,
        targetText: String?
    ): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            if (targetText == null) return node
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text.contains(targetText, ignoreCase = true) ||
                desc.contains(targetText, ignoreCase = true)
            ) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child, targetText)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    // ─── Global Actions ──────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ─── Gestures ────────────────────────────────────────────────

    /** Swipe gesture from start to end coordinates. */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Long press at coordinates (1000ms gesture). */
    fun longPressAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ─── App Info ────────────────────────────────────────────────

    /** Get the currently focused app's package name. */
    fun getCurrentPackage(): String? {
        var ownApplicationSeen = false
        var fallbackApplication: String? = null

        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString()
            root.recycle()

            if (pkg == null || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue
            }

            if (window.isActive || window.isFocused) {
                return pkg
            }

            if (pkg == ownPackageName) {
                ownApplicationSeen = true
            } else if (fallbackApplication == null) {
                fallbackApplication = pkg
            }
        }

        return fallbackApplication ?: if (ownApplicationSeen) ownPackageName else null
    }
}

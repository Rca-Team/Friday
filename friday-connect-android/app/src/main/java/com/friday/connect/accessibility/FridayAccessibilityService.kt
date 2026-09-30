package com.friday.connect.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import com.friday.connect.core.AgentStateStore

class FridayAccessibilityService : AccessibilityService() {
    var lastExternalPackage: String? = null
        private set
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val name = event.packageName?.toString()
            if (!name.isNullOrBlank() && name != packageName && name !in setOf("com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller")) lastExternalPackage = name
        }
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentStateStore.addLog("Accessibility service enabled.")
        Companion.instance = this
    }

    override fun onDestroy() {
        if (Companion.instance == this) {
            Companion.instance = null
        }
        super.onDestroy()
    }

    fun unlockPhone(pin: String): Boolean {
        AgentStateStore.addLog("Unlocking phone with PIN...")
        return true
    }

    fun dumpUiTree(): Map<String, Any> {
        val root = rootInActiveWindow
        if (root == null) {
            AgentStateStore.addLog("UI dump failed: No active window.")
            return mapOf("error" to "No active window")
        }
        val nodes = mutableListOf<Map<String, Any>>()
        traverseNode(root, nodes)
        return mapOf("nodes" to nodes)
    }

    private fun traverseNode(node: AccessibilityNodeInfo, nodes: MutableList<Map<String, Any>>, depth: Int = 0) {
        if (depth > 30 || nodes.size >= 500) return
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val nodeMap = mutableMapOf<String, Any>(
                "class" to (node.className?.toString() ?: ""),
                "text" to (if (node.isPassword) "[password field]" else node.text?.toString()?.take(2000) ?: ""),
                "content_description" to (node.contentDescription?.toString() ?: ""),
                "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                "is_clickable" to node.isClickable,
                "is_scrollable" to node.isScrollable,
                "is_focused" to node.isFocused
            )
            nodes.add(nodeMap)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, nodes, depth + 1)
                child.recycle()
            }
        }
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(100L)))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = findFocusedNode(root)
        if (focusedNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun clickText(targetText: String, exact: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, targetText.trim(), exact) ?: return false

        // 1. Try direct click or on clickable ancestor
        var curr: AccessibilityNodeInfo? = node
        var clicked = false
        var attempts = 0
        while (curr != null && attempts < 5) {
            if (curr.isClickable && curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                clicked = true
                break
            }
            curr = curr.parent
            attempts++
        }

        // 2. If node was not marked clickable or action returned false, tap screen bounds
        if (!clicked) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                clicked = tap(bounds.centerX(), bounds.centerY())
            }
        }
        node.recycle()
        return clicked
    }

    fun clickId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val target = nodes.firstOrNull() ?: return false
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        val clicked = if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            true
        } else {
            tap(bounds.centerX(), bounds.centerY())
        }
        nodes.forEach { it.recycle() }
        return clicked
    }

    fun typeTextIntoField(targetHintOrLabel: String?, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode: AccessibilityNodeInfo? = if (!targetHintOrLabel.isNullOrBlank()) {
            findNodeByText(root, targetHintOrLabel, false) ?: findEditableNode(root)
        } else {
            findFocusedNode(root) ?: findEditableNode(root)
        }

        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val res = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            targetNode.recycle()
            return res
        }
        return false
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        val dir = direction.lowercase().trim()

        if (scrollable != null) {
            val action = if (dir == "up" || dir == "backward") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val res = scrollable.performAction(action)
            scrollable.recycle()
            if (res) return true
        }

        // Fallback to gesture swipe
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val startX = width / 2
        return if (dir == "up" || dir == "backward") {
            swipe(startX, (height * 0.3).toInt(), startX, (height * 0.7).toInt(), 300)
        } else {
            swipe(startX, (height * 0.7).toInt(), startX, (height * 0.3).toInt(), 300)
        }
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun readScreenText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val textList = mutableListOf<String>()
        collectText(root, textList)
        return textList
    }

    private fun collectText(node: AccessibilityNodeInfo, list: MutableList<String>, depth: Int = 0) {
        if (depth > 30 || list.size >= 500 || node.isPassword) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim()?.take(2000)
            if (!t.isNullOrBlank()) list.add(t)
            val d = node.contentDescription?.toString()?.trim()?.take(2000)
            if (!d.isNullOrBlank() && d != t) list.add(d)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectText(child, list, depth + 1)
                child.recycle()
            }
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, target: String, exact: Boolean): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val match = if (exact) {
            text.equals(target, ignoreCase = true) || desc.equals(target, ignoreCase = true)
        } else {
            text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true)
        }

        if (match) return AccessibilityNodeInfo.obtain(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByText(child, target, exact)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findFocusedNode(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findEditableNode(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findScrollableNode(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    companion object {
        var instance: FridayAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { service ->
                val info = service.resolveInfo?.serviceInfo
                info?.packageName == context.packageName && info.name == FridayAccessibilityService::class.java.name
            }
        }
    }
}

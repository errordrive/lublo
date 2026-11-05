package com.lublo.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Snapshot of the current screen: compact text for the model + node handles for acting. */
class Screen(
    val pkg: String,
    val text: String,
    val nodes: Map<Int, AccessibilityNodeInfo>,
    val centers: Map<Int, IntArray>,
    val labels: Map<Int, String>
)

object ScreenReader {
    private const val MAX_NODES = 120
    private const val MAX_DEPTH = 40

    fun read(svc: AccessibilityService): Screen? {
        val root = svc.rootInActiveWindow ?: return null
        val sb = StringBuilder()
        val nodes = HashMap<Int, AccessibilityNodeInfo>()
        val centers = HashMap<Int, IntArray>()
        val labels = HashMap<Int, String>()
        var n = 0
        val r = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (n >= MAX_NODES || depth > MAX_DEPTH || !node.isVisibleToUser) return
            val raw = if (node.isPassword) "(password field)"
            else (node.text ?: node.contentDescription)?.toString() ?: ""
            val label = raw.replace('\n', ' ').trim().take(60)
            val clickable = node.isClickable || node.isLongClickable
            val interesting = clickable || node.isEditable || node.isScrollable ||
                node.isCheckable || label.isNotEmpty()
            if (interesting) {
                val id = ++n
                node.getBoundsInScreen(r)
                val cx = r.centerX()
                val cy = r.centerY()
                nodes[id] = node
                centers[id] = intArrayOf(cx, cy)
                labels[id] = label
                val flags = ArrayList<String>(4)
                if (clickable) flags.add("click")
                if (node.isEditable) flags.add("edit")
                if (node.isScrollable) flags.add("scroll")
                if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "unchecked")
                if (node.isFocused) flags.add("focused")
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                sb.append('[').append(id).append("] ").append(cls)
                if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
                if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
                sb.append(" @").append(cx).append(',').append(cy).append('\n')
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        return Screen(root.packageName?.toString() ?: "?", sb.toString(), nodes, centers, labels)
    }
}

package com.vyzorix.audiorouter.common.extensions

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * `AccessibilityService` helpers used by Layer 6's `AutomationOrchestrator`.
 *
 * These helpers are pure read-only — they extract text and metadata from
 * `AccessibilityEvent` / `AccessibilityNodeInfo`. Side-effects (clicks,
 * scrolls, navigation) live in Layer 6's automation code so the write
 * surface is auditable in one place.
 */

/**
 * Returns the package name of the window the event originated from, or
 * `null` if unavailable. Used by `AutomationSafetyGate` to refuse
 * automation outside the allow-listed target packages.
 */
public fun AccessibilityEvent.getWindowPackageName(): String? = packageName?.toString()

/**
 * Extracts the visible dialog text from a root [AccessibilityNodeInfo],
 * concatenating every leaf node's `text` + `contentDescription`.
 *
 * Used to identify which system permission dialog is on screen (e.g.
 * "Allow Vyzorix to record audio?") so `AutomationOrchestrator` picks
 * the right text-match script.
 *
 * Bounded depth + breadth to keep the helper from blowing the stack on
 * adversarially nested view hierarchies.
 */
public fun AccessibilityNodeInfo.extractDialogText(
    maxDepth: Int = DEFAULT_ACCESSIBILITY_MAX_DEPTH,
    maxNodes: Int = DEFAULT_ACCESSIBILITY_MAX_NODES,
): String {
    val builder = StringBuilder()
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.addLast(this to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited < maxNodes) {
        val (node, depth) = queue.removeFirst()
        visited++
        if (depth > maxDepth) continue
        node.text?.takeIf { it.isNotBlank() }?.let { text ->
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(text)
        }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { cd ->
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(cd)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            queue.addLast(child to depth + 1)
        }
    }
    return builder.toString()
}

/** Default depth bound for [extractDialogText]. System permission dialogs are shallow. */
public const val DEFAULT_ACCESSIBILITY_MAX_DEPTH: Int = 12

/** Default node bound for [extractDialogText]. Generous; protects against pathological trees. */
public const val DEFAULT_ACCESSIBILITY_MAX_NODES: Int = 256

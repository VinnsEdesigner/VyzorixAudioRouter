// UiInteractionSnapshot — captures the AccessibilityNodeInfo tree at the
// moment an auto-click is fired, so post-mortem analysis of failed
// projection-dialog interactions has the data it needs.
//
// Per doc/BUILD_ORDER.md §Layer 4 ("UiInteractionSnapshot so the
// projection dialog auto-clicks 'Start Now' headlessly"): this is the
// forensic surface, not the action surface. The actual click happens
// in [AccessibilityGestureQueue].
//
// Threading: snapshot capture runs on whichever accessibility callback
// thread fired the event. Snapshots are immutable; readers can be on
// any thread.

package com.vyzorix.audiorouter.services.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference

/** Frozen node tree at the moment of capture. */
public data class UiInteractionSnapshot(
    public val captureEpochMs: Long,
    public val packageName: String?,
    public val rootClassName: String?,
    public val nodes: List<NodeRecord>,
) {

    /** Lightweight, GC-friendly node descriptor — no AccessibilityNodeInfo retention. */
    public data class NodeRecord(
        public val className: String?,
        public val text: String?,
        public val contentDescription: String?,
        public val viewIdResourceName: String?,
        public val isClickable: Boolean,
        public val isEnabled: Boolean,
        public val depth: Int,
    )

    /** Convenience: find all node records whose [NodeRecord.text] matches case-insensitively. */
    public fun findByTextIgnoreCase(needle: String): List<NodeRecord> =
        nodes.filter { it.text?.equals(needle, ignoreCase = true) == true }

    public fun findByContentDescriptionIgnoreCase(needle: String): List<NodeRecord> =
        nodes.filter { it.contentDescription?.equals(needle, ignoreCase = true) == true }

    public companion object {
        /**
         * Capture a snapshot from an [AccessibilityNodeInfo] root. We walk
         * the tree up to [maxDepth] levels and at most [maxNodes] nodes
         * total to bound memory cost.
         */
        public fun capture(
            root: AccessibilityNodeInfo?,
            packageName: String? = root?.packageName?.toString(),
            maxDepth: Int = DEFAULT_MAX_DEPTH,
            maxNodes: Int = DEFAULT_MAX_NODES,
        ): UiInteractionSnapshot {
            val nodes = ArrayList<NodeRecord>(64)
            if (root != null) {
                walkBoundedTree(
                    node = root,
                    depth = 0,
                    nodes = nodes,
                    maxDepth = maxDepth,
                    maxNodes = maxNodes,
                )
            } else {
                DaemonLogger.get().warn(TAG, "snapshot.capture.null_root")
            }
            return UiInteractionSnapshot(
                captureEpochMs = System.currentTimeMillis(),
                packageName = packageName,
                rootClassName = root?.className?.toString(),
                nodes = nodes,
            )
        }

        private fun walkBoundedTree(
            node: AccessibilityNodeInfo,
            depth: Int,
            nodes: MutableList<NodeRecord>,
            maxDepth: Int,
            maxNodes: Int,
        ) {
            if (nodes.size >= maxNodes) return
            nodes.add(
                NodeRecord(
                    className = node.className?.toString(),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    viewIdResourceName = node.viewIdResourceName,
                    isClickable = node.isClickable,
                    isEnabled = node.isEnabled,
                    depth = depth,
                ),
            )
            if (depth >= maxDepth) return
            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (nodes.size >= maxNodes) return
                val child = node.getChild(i) ?: continue
                try {
                    walkBoundedTree(
                        node = child,
                        depth = depth + 1,
                        nodes = nodes,
                        maxDepth = maxDepth,
                        maxNodes = maxNodes,
                    )
                } finally {
                    // AccessibilityNodeInfo getChild returns a NEW instance
                    // that must be recycled — on Android 33 the recycle() is
                    // a no-op but we call it for forward compatibility.
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }

        public const val DEFAULT_MAX_DEPTH: Int = 12
        public const val DEFAULT_MAX_NODES: Int = 256
        private const val TAG: String = "UiInteractionSnapshot"
    }
}

/**
 * Holder for the most recent UI interaction snapshot. Higher layers
 * (CrashTraceStore, RoutingLogCollector) can read [latestSnapshot] when
 * assembling a forensic bundle.
 */
public class UiInteractionRecorder {

    private val latest: AtomicReference<UiInteractionSnapshot?> = AtomicReference(null)
    private val captureTimestampMillis: AtomicReference<Long?> = AtomicReference(null)

    /** Replace the in-memory snapshot. */
    public fun record(snapshot: UiInteractionSnapshot) {
        latest.set(snapshot)
        captureTimestampMillis.set(SystemClock.elapsedRealtime())
    }

    /** Read the most recent snapshot, or null if none recorded yet. */
    public val latestSnapshot: UiInteractionSnapshot?
        get() = latest.get()

    /** Elapsed-realtime ms at which the snapshot was recorded. */
    public val latestSnapshotCapturedAtUptimeMs: Long?
        get() = captureTimestampMillis.get()
}

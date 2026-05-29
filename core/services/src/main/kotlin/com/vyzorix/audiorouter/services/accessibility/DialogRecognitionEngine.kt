// DialogRecognitionEngine — locates the "Start Now" button on Android's
// MediaProjection consent dialog.
//
// Android shows the dialog as `com.android.systemui` with a confirm
// button that has text "Start now" (en-US) or its localized variant.
// The button's view-id-resource-name is canonically
// `android:id/button1` (positive button in AlertDialog) on stock
// Android 13+ — but Nokia C22 ships a customized SystemUI where the
// id can differ. We therefore search by MULTIPLE strategies in
// descending preference:
//
//   1. By view-id-resource-name (exact match against a known set).
//   2. By text content (case-insensitive, against a localized whitelist).
//   3. By content-description (case-insensitive).
//   4. By position (last clickable button in the dialog tree).
//
// Multiple-strategy search makes the auto-click resilient against
// localization and OEM theming. Failures fall back to "no button found"
// and the caller logs a forensic snapshot for manual review.
//
// Per doc/BUILD_ORDER.md §Layer 4 + MEDIA_PROJECTION_FLOW.md §2 (Phase 1).

package com.vyzorix.audiorouter.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Outcome of [DialogRecognitionEngine.locateConfirmButton]. */
public sealed interface DialogRecognitionResult {
    /** Found a clickable button matching one of the strategies. */
    public data class Found(
        public val node: AccessibilityNodeInfo,
        public val strategy: MatchStrategy,
    ) : DialogRecognitionResult

    /** No matching button. Caller should record a forensic snapshot. */
    public object NotFound : DialogRecognitionResult
}

/** Which strategy matched (recorded for forensics). */
public enum class MatchStrategy {
    VIEW_ID,
    TEXT,
    CONTENT_DESCRIPTION,
    POSITIONAL_FALLBACK,
}

/**
 * Stateless dialog recogniser. One instance is fine for the daemon
 * process.
 */
public class DialogRecognitionEngine(
    private val confirmTexts: Set<String> = DEFAULT_CONFIRM_TEXTS,
    private val confirmViewIds: Set<String> = DEFAULT_CONFIRM_VIEW_IDS,
) {

    /**
     * Walk the supplied node tree looking for the projection-dialog
     * "Start Now" button. Returns the first match by strategy preference.
     *
     * The returned [AccessibilityNodeInfo] (if Found) is the original node
     * supplied by the system — the caller MUST NOT recycle it; the
     * Accessibility framework owns its lifecycle.
     */
    public fun locateConfirmButton(root: AccessibilityNodeInfo?): DialogRecognitionResult {
        if (root == null) {
            DaemonLogger.get().warn(TAG, "recognise.null_root")
            return DialogRecognitionResult.NotFound
        }
        val byViewId = findFirst(root) { node ->
            val resName = node.viewIdResourceName ?: return@findFirst false
            resName in confirmViewIds
        }
        if (byViewId != null) {
            DaemonLogger.get().info(
                TAG,
                "recognise.found strategy=VIEW_ID viewId=${byViewId.viewIdResourceName}",
            )
            return DialogRecognitionResult.Found(node = byViewId, strategy = MatchStrategy.VIEW_ID)
        }

        val byText = findFirst(root) { node ->
            val text = node.text?.toString() ?: return@findFirst false
            confirmTexts.any { it.equals(text, ignoreCase = true) }
        }
        if (byText != null) {
            DaemonLogger.get().info(
                TAG,
                "recognise.found strategy=TEXT text=${byText.text}",
            )
            return DialogRecognitionResult.Found(node = byText, strategy = MatchStrategy.TEXT)
        }

        val byContentDesc = findFirst(root) { node ->
            val desc = node.contentDescription?.toString() ?: return@findFirst false
            confirmTexts.any { it.equals(desc, ignoreCase = true) }
        }
        if (byContentDesc != null) {
            DaemonLogger.get().info(
                TAG,
                "recognise.found strategy=CONTENT_DESCRIPTION desc=${byContentDesc.contentDescription}",
            )
            return DialogRecognitionResult.Found(
                node = byContentDesc,
                strategy = MatchStrategy.CONTENT_DESCRIPTION,
            )
        }

        // Positional fallback: the LAST clickable button in the tree.
        val lastClickable = findLast(root) { node ->
            node.isClickable && node.isEnabled
        }
        if (lastClickable != null) {
            DaemonLogger.get().warn(
                TAG,
                "recognise.found strategy=POSITIONAL_FALLBACK class=${lastClickable.className}",
            )
            return DialogRecognitionResult.Found(
                node = lastClickable,
                strategy = MatchStrategy.POSITIONAL_FALLBACK,
            )
        }
        DaemonLogger.get().warn(TAG, "recognise.not_found")
        return DialogRecognitionResult.NotFound
    }

    /** Pre-order traversal returning the first node matching [predicate]. */
    private fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        val n = root.childCount
        for (i in 0 until n) {
            val child = root.getChild(i) ?: continue
            val match = findFirst(child, predicate)
            if (match != null) return match
        }
        return null
    }

    /** Post-order traversal returning the LAST node matching [predicate]. */
    private fun findLast(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        val n = root.childCount
        for (i in 0 until n) {
            val child = root.getChild(i) ?: continue
            val childMatch = findLast(child, predicate)
            if (childMatch != null) found = childMatch
        }
        if (predicate(root)) {
            // Prefer the deepest match — children win over self.
            if (found == null) found = root
        }
        return found
    }

    public companion object {
        /** Localized "Start Now" strings. Extend as needed. */
        public val DEFAULT_CONFIRM_TEXTS: Set<String> = setOf(
            "Start now",
            "Start Now",
            "START NOW",
            "Begin",
            "Allow",
            "OK",
        )
        /** Canonical AlertDialog positive-button ids across vendors. */
        public val DEFAULT_CONFIRM_VIEW_IDS: Set<String> = setOf(
            "android:id/button1",
            "com.android.systemui:id/button1",
            "com.android.systemui:id/start_now_button",
        )
        private const val TAG: String = "DialogRecognitionEngine"
    }
}

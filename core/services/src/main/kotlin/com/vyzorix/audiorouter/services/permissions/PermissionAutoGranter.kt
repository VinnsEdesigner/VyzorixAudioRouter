// PermissionAutoGranter — wires together the dialog recogniser, gesture
// queue, and snapshot recorder so the projection-dialog auto-click can
// be invoked from one place.
//
// Flow:
//   1. Accessibility callback fires with a TYPE_WINDOW_STATE_CHANGED
//      whose package is "com.android.systemui".
//   2. Caller invokes [tryAutoClick(rootNode)].
//   3. We capture a UiInteractionSnapshot for forensics, then run the
//      dialog recogniser. If a button is found, we enqueue a click
//      gesture and dispatch it.
//   4. Outcome is logged + the snapshot is preserved.
//
// Per doc/BUILD_ORDER.md §Layer 4 ("PermissionAutoGranter").

package com.vyzorix.audiorouter.services.permissions

import android.view.accessibility.AccessibilityNodeInfo
import com.vyzorix.audiorouter.services.accessibility.AccessibilityGestureQueue
import com.vyzorix.audiorouter.services.accessibility.DialogRecognitionEngine
import com.vyzorix.audiorouter.services.accessibility.DialogRecognitionResult
import com.vyzorix.audiorouter.services.accessibility.GestureDispatchResult
import com.vyzorix.audiorouter.services.accessibility.QueuedGesture
import com.vyzorix.audiorouter.services.accessibility.UiInteractionRecorder
import com.vyzorix.audiorouter.services.accessibility.UiInteractionSnapshot
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Outcome of [PermissionAutoGranter.tryAutoClick]. */
public sealed interface AutoClickResult {
    public data class Clicked(public val strategy: String) : AutoClickResult
    public data class ButtonNotFound(public val snapshot: UiInteractionSnapshot) : AutoClickResult
    public data class DispatchFailed(public val reason: String) : AutoClickResult
}

/**
 * Orchestrator for one auto-click cycle against the projection dialog.
 *
 * Stateful only via the dependency [UiInteractionRecorder]; the auto-
 * granter itself does not maintain state.
 */
public class PermissionAutoGranter(
    private val recogniser: DialogRecognitionEngine,
    private val gestureQueue: AccessibilityGestureQueue,
    private val interactionRecorder: UiInteractionRecorder,
) {

    /**
     * Try to auto-click the projection-confirm button under [rootNode].
     * Returns the outcome.
     */
    public fun tryAutoClick(rootNode: AccessibilityNodeInfo?): AutoClickResult {
        val snapshot = UiInteractionSnapshot.capture(root = rootNode)
        interactionRecorder.record(snapshot)
        val result = recogniser.locateConfirmButton(rootNode)
        if (result is DialogRecognitionResult.NotFound) {
            DaemonLogger.get().warn(TAG, "autoclick.button_not_found")
            return AutoClickResult.ButtonNotFound(snapshot = snapshot)
        }
        val found = result as DialogRecognitionResult.Found
        val admitted = gestureQueue.enqueue(
            node = found.node,
            action = QueuedGesture.ACTION_CLICK,
            origin = "projection_dialog_${found.strategy.name.lowercase()}",
        )
        if (!admitted) {
            DaemonLogger.get().warn(
                TAG,
                "autoclick.queue_full strategy=${found.strategy}",
            )
        }
        return when (val dispatched = gestureQueue.dispatchOnce()) {
            is GestureDispatchResult.Dispatched -> {
                DaemonLogger.get().info(
                    TAG,
                    "autoclick.success strategy=${found.strategy} origin=${dispatched.origin}",
                )
                AutoClickResult.Clicked(strategy = found.strategy.name)
            }
            is GestureDispatchResult.RateLimited -> {
                DaemonLogger.get().info(
                    TAG,
                    "autoclick.rate_limited sleepMs=${dispatched.sleepMs} origin=${dispatched.origin}",
                )
                AutoClickResult.DispatchFailed("rate_limited:${dispatched.sleepMs}")
            }
            is GestureDispatchResult.Failed -> {
                DaemonLogger.get().warn(
                    TAG,
                    "autoclick.failed origin=${dispatched.origin}",
                )
                AutoClickResult.DispatchFailed(reason = "performAction_returned_false")
            }
            is GestureDispatchResult.QueueEmpty -> {
                DaemonLogger.get().warn(TAG, "autoclick.queue_empty_unexpected")
                AutoClickResult.DispatchFailed(reason = "queue_empty")
            }
        }
    }

    private companion object {
        const val TAG: String = "PermissionAutoGranter"
    }
}

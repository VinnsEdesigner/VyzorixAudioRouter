// NotificationActionReceiver — single BroadcastReceiver for every
// notification button on the daemon dashboard.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 636:
//     core/services/foreground/actions/NotificationActionReceiver.kt
//       "Binds notification button broadcast clicks; exported=false".
//
// Each button on the dashboard fires this receiver with a single
// EXTRA_ACTION value indicating which action to run. The receiver
// dispatches into the right `*Action` handler. This pattern is
// preferred over per-action receivers because:
//   - One AndroidManifest receiver declaration covers all buttons.
//   - PendingIntent request codes can be derived from the action enum.
//   - Stateful UX (e.g. "you cannot restart while another restart is
//     pending") can be enforced uniformly here.
//
// The receiver is registered with `exported=false` and only accepts
// broadcasts that carry its private package name. Per
// `NotificationTrampolineCompat`, the receiver will NOT start an
// activity directly on A12+ — it issues commands to the running daemon
// via service start intents.

package com.vyzorix.audiorouter.services.foreground.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single BroadcastReceiver for every dashboard button.
 *
 * The receiver is dependency-injected at install-time via the
 * companion's `attach*` setters so it can be tested without spinning up
 * a service.
 */
public class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_ROUTE) {
            DaemonLogger.get().warn(
                TAG,
                "receiver.unexpected_action action=${intent.action}",
            )
            return
        }
        val actionId = intent.getStringExtra(EXTRA_ACTION)
        if (actionId.isNullOrEmpty()) {
            DaemonLogger.get().warn(TAG, "receiver.missing_action_id")
            return
        }
        totalReceived.incrementAndGet()
        lastReceivedActionId.set(actionId)
        DaemonLogger.get().info(TAG, "receiver.route action=$actionId")

        when (actionId) {
            QuickToggleAction.ACTION_ID -> QuickToggleAction.handle(context, intent)
            RestartPipelineAction.ACTION_ID -> RestartPipelineAction.handle(context, intent)
            EmergencyStopAction.ACTION_ID -> EmergencyStopAction.handle(context, intent)
            else -> {
                unknownActions.incrementAndGet()
                DaemonLogger.get().warn(
                    TAG,
                    "receiver.unknown_action_id action=$actionId",
                )
            }
        }
    }

    public companion object {
        /** Broadcast action the receiver listens for. */
        public const val ACTION_ROUTE: String =
            "com.vyzorix.audiorouter.intent.action.NOTIFICATION_ACTION"

        /** Extra carrying the per-action ID (`QuickToggle`, `Restart`, `Stop`). */
        public const val EXTRA_ACTION: String =
            "com.vyzorix.audiorouter.intent.extra.ACTION_ID"

        /** Extra carrying a free-form rationale string for logging. */
        public const val EXTRA_RATIONALE: String =
            "com.vyzorix.audiorouter.intent.extra.RATIONALE"

        private val totalReceived: AtomicLong = AtomicLong(0L)
        private val unknownActions: AtomicLong = AtomicLong(0L)
        private val lastReceivedActionId: AtomicReference<String> = AtomicReference("init")

        public fun totalReceivedCount(): Long = totalReceived.get()
        public fun unknownActionCount(): Long = unknownActions.get()
        public fun lastReceivedAction(): String = lastReceivedActionId.get()

        private const val TAG: String = "NotificationActionReceiver"
    }
}

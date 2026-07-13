// NotificationActionReceiver — single BroadcastReceiver for every
// notification button on the daemon dashboard.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 636:
//     core/services/foreground/actions/NotificationActionReceiver.kt
//       "Binds notification button broadcast clicks; exported=false".
//
// The receiver is deliberately tiny: validate the private dashboard
// broadcast, look up the action handler, and invoke that handler. The
// actual command semantics live in QuickToggleAction / RestartPipelineAction /
// EmergencyStopAction so button routing is testable without starting a service.

package com.vyzorix.audiorouter.services.foreground.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Single exported=false receiver for every dashboard button. */
public class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?): Unit {
        if (context == null || intent == null) return
        dispatch(context, intent)
    }

    public companion object {
        public const val ACTION_ROUTE: String =
            "com.vyzorix.audiorouter.intent.action.NOTIFICATION_ACTION"
        public const val EXTRA_ACTION: String =
            "com.vyzorix.audiorouter.intent.extra.ACTION_ID"
        public const val EXTRA_RATIONALE: String =
            "com.vyzorix.audiorouter.intent.extra.RATIONALE"

        private val totalReceived: AtomicLong = AtomicLong(0L)
        private val totalHandled: AtomicLong = AtomicLong(0L)
        private val unknownActions: AtomicLong = AtomicLong(0L)
        private val rejectedBroadcasts: AtomicLong = AtomicLong(0L)
        private val lastReceivedActionId: AtomicReference<String> = AtomicReference("init")
        private val handlers: MutableMap<String, NotificationActionHandler> = defaultHandlers().toMutableMap()

        /** Pure dispatch entry point used by tests and [onReceive]. */
        public fun dispatch(context: Context, intent: Intent): NotificationDispatchResult {
            totalReceived.incrementAndGet()
            if (intent.action != ACTION_ROUTE) {
                rejectedBroadcasts.incrementAndGet()
                DaemonLogger.get().warn(TAG, "receiver.unexpected_action action=${intent.action}")
                return NotificationDispatchResult.Rejected("unexpected_action")
            }
            val actionId = intent.getStringExtra(EXTRA_ACTION)
            if (actionId.isNullOrBlank()) {
                rejectedBroadcasts.incrementAndGet()
                DaemonLogger.get().warn(TAG, "receiver.missing_action_id")
                return NotificationDispatchResult.Rejected("missing_action")
            }
            lastReceivedActionId.set(actionId)
            val handler = synchronized(handlers) { handlers[actionId] }
            if (handler == null) {
                unknownActions.incrementAndGet()
                DaemonLogger.get().warn(TAG, "receiver.unknown_action_id action=$actionId")
                return NotificationDispatchResult.Unknown(actionId)
            }
            handler.handle(context, intent)
            totalHandled.incrementAndGet()
            return NotificationDispatchResult.Handled(actionId)
        }

        /** Test hook: replace one handler without altering manifest wiring. */
        public fun attachHandler(actionId: String, handler: NotificationActionHandler): Unit = synchronized(handlers) {
            handlers[actionId] = handler
        }

        /** Test hook: restore production handlers and reset counters. */
        public fun resetForTests(): Unit = synchronized(handlers) {
            handlers.clear()
            handlers.putAll(defaultHandlers())
            totalReceived.set(0L)
            totalHandled.set(0L)
            unknownActions.set(0L)
            rejectedBroadcasts.set(0L)
            lastReceivedActionId.set("init")
        }

        public fun totalReceivedCount(): Long = totalReceived.get()
        public fun totalHandledCount(): Long = totalHandled.get()
        public fun unknownActionCount(): Long = unknownActions.get()
        public fun rejectedBroadcastCount(): Long = rejectedBroadcasts.get()
        public fun lastReceivedAction(): String = lastReceivedActionId.get()

        private fun defaultHandlers(): Map<String, NotificationActionHandler> = mapOf(
            QuickToggleAction.ACTION_ID to QuickToggleAction,
            RestartPipelineAction.ACTION_ID to RestartPipelineAction,
            EmergencyStopAction.ACTION_ID to EmergencyStopAction,
        )

        private const val TAG: String = "NotificationActionReceiver"
    }
}

public fun interface NotificationActionHandler {
    public fun handle(context: Context, intent: Intent): Unit
}

public sealed class NotificationDispatchResult(public val handled: Boolean) {
    public data class Handled(public val actionId: String) : NotificationDispatchResult(true)
    public data class Unknown(public val actionId: String) : NotificationDispatchResult(false)
    public data class Rejected(public val reason: String) : NotificationDispatchResult(false)
}

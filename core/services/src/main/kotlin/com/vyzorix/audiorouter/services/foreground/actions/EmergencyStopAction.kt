// EmergencyStopAction — request RecoveryCoordinator to fully shut the
// daemon down (no auto-restart).
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 639:
//     core/services/foreground/actions/EmergencyStopAction.kt
//       "Hard-stop service + cancel pending workers".
//
// User experience: a dashboard button labelled "Stop" that
//   1. disengages SpeakerForceManager,
//   2. tears down the capture pipeline,
//   3. cancels all pending RecoveryCoordinator restart attempts,
//   4. calls `Service.stopSelf()` so START_STICKY does NOT respawn us.
//
// Like the other actions, the receiver forwards a service intent and
// the service does the heavy lifting in onStartCommand.

package com.vyzorix.audiorouter.services.foreground.actions

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.compat.PendingIntentCompatPolicy
import com.vyzorix.audiorouter.services.foreground.PersistentAudioService
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Stateless action handler. */
public object EmergencyStopAction : NotificationActionHandler {

    public const val ACTION_ID: String = "emergency_stop"

    public const val ACTION_SERVICE_EMERGENCY_STOP: String =
        "com.vyzorix.audiorouter.intent.action.SVC_EMERGENCY_STOP"

    public const val REQUEST_CODE: Int = 0x520_003

    public fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(NotificationActionReceiver.ACTION_ROUTE).apply {
            `package` = context.packageName
            component = ComponentName(context, NotificationActionReceiver::class.java)
            putExtra(NotificationActionReceiver.EXTRA_ACTION, ACTION_ID)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntentCompatPolicy.broadcastFlags(),
        )
    }

    public override fun handle(context: Context, intent: Intent): Unit {
        DaemonLogger.get().warn(TAG, "emergency_stop.handle")
        forwardToService(context, buildServiceIntent(context, intent))
    }


    /** Build the service command without starting it; used by tests and the receiver. */
    public fun buildServiceIntent(context: Context, routedIntent: Intent): Intent {
        val rationale = routedIntent.getStringExtra(NotificationActionReceiver.EXTRA_RATIONALE) ?: "user_requested"
        return Intent(context, PersistentAudioService::class.java).apply {
            action = ACTION_SERVICE_EMERGENCY_STOP
            putExtra(NotificationActionReceiver.EXTRA_RATIONALE, rationale)
        }
    }

    private fun forwardToService(context: Context, intent: Intent): Unit {
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "emergency_stop.start_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
        }
    }

    private const val TAG: String = "EmergencyStopAction"
}

// QuickToggleAction — toggle speaker-forcing engaged/disengaged from
// the dashboard.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 637:
//     core/services/foreground/actions/QuickToggleAction.kt
//       "Instantly toggles speaker-forcing; updates RemoteViews".
//
// User experience: a single dashboard button labelled "Pause / Resume"
// that flips the daemon between [DaemonState.RUNNING] and a paused
// state where SpeakerForceManager is disengaged but the service stays
// alive.
//
// Implementation: the receiver forwards an Intent to
// `PersistentAudioService` with action `ACTION_QUICK_TOGGLE`. The
// service's onStartCommand inspects the action and calls
// `speakerForceManager.engage()` / `disengage()` accordingly.

package com.vyzorix.audiorouter.services.foreground.actions

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.compat.PendingIntentCompatPolicy
import com.vyzorix.audiorouter.services.foreground.PersistentAudioService
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Stateless action handler. */
public object QuickToggleAction : NotificationActionHandler {

    /** Action ID carried in `NotificationActionReceiver.EXTRA_ACTION`. */
    public const val ACTION_ID: String = "quick_toggle"

    /** Service intent action consumed by PersistentAudioService.onStartCommand. */
    public const val ACTION_SERVICE_TOGGLE: String =
        "com.vyzorix.audiorouter.intent.action.SVC_QUICK_TOGGLE"

    /** Request code for the PendingIntent (must be unique per action). */
    public const val REQUEST_CODE: Int = 0x520_001

    /**
     * Build the PendingIntent attached to the dashboard's
     * Pause/Resume button.
     */
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

    /** Handle a routed broadcast. */
    public override fun handle(context: Context, intent: Intent): Unit {
        DaemonLogger.get().info(TAG, "quick_toggle.handle")
        forwardToService(context, buildServiceIntent(context, intent))
    }


    /** Build the service command without starting it; used by tests and the receiver. */
    public fun buildServiceIntent(context: Context, routedIntent: Intent): Intent {
        val rationale = routedIntent.getStringExtra(NotificationActionReceiver.EXTRA_RATIONALE)
        return Intent(context, PersistentAudioService::class.java).apply {
            action = ACTION_SERVICE_TOGGLE
            putExtra(NotificationActionReceiver.EXTRA_RATIONALE, rationale)
        }
    }

    private fun forwardToService(context: Context, intent: Intent): Unit {
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "quick_toggle.start_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
        }
    }

    private const val TAG: String = "QuickToggleAction"
}

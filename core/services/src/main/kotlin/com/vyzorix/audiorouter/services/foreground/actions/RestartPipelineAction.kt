// RestartPipelineAction — request RecoveryCoordinator to restart the
// audio pipeline.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 638:
//     core/services/foreground/actions/RestartPipelineAction.kt
//       "Manually restart the pipeline".
//
// This is the user-facing "kick it" button. It does NOT directly
// restart anything — that would violate ADR-0007 Layer A authority.
// Instead, the action forwards a service intent that the service
// routes into [RecoveryCoordinator.requestRestart] (which then runs
// the full risk-score → cooldown → restart pipeline).

package com.vyzorix.audiorouter.services.foreground.actions

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.compat.PendingIntentCompatPolicy
import com.vyzorix.audiorouter.services.foreground.PersistentAudioService
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Stateless action handler. */
public object RestartPipelineAction {

    public const val ACTION_ID: String = "restart_pipeline"

    public const val ACTION_SERVICE_RESTART: String =
        "com.vyzorix.audiorouter.intent.action.SVC_RESTART_PIPELINE"

    public const val REQUEST_CODE: Int = 0x520_002

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

    public fun handle(context: Context, intent: Intent) {
        DaemonLogger.get().info(TAG, "restart_pipeline.handle")
        val svcIntent = Intent(context, PersistentAudioService::class.java).apply {
            action = ACTION_SERVICE_RESTART
            putExtra(
                NotificationActionReceiver.EXTRA_RATIONALE,
                intent.getStringExtra(NotificationActionReceiver.EXTRA_RATIONALE)
                    ?: "user_requested",
            )
        }
        forwardToService(context, svcIntent)
    }

    private fun forwardToService(context: Context, intent: Intent) {
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "restart_pipeline.start_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
        }
    }

    private const val TAG: String = "RestartPipelineAction"
}

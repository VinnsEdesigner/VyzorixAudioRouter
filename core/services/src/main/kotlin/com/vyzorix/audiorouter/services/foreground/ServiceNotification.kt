// ServiceNotification — builds the daemon's "I'm running" notification.
//
// Why a separate file (vs inlining in PersistentAudioService): the
// notification content surface is what the dashboard (Layer 5+) and the
// crash recovery flow (Layer 5+) will mutate. Having a single factory
// makes the post-Layer-3 wiring trivial — those layers replace this
// file's body with a content-rich version.
//
// Layer 3 surface: title + "Speaker route active" text. No actions, no
// progress bar, no dashboard deep-link (yet). The tap target points at
// the app's system settings entry so users have a recourse if anything
// behaves unexpectedly during the on-device acceptance window.

package com.vyzorix.audiorouter.services.foreground

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.common.utils.IntentUtils
import com.vyzorix.audiorouter.common.utils.NotificationChannelManager
import com.vyzorix.audiorouter.common.utils.NotificationHelper

/** Builds the Layer-3 foreground-service notification. */
public object ServiceNotification {

    /**
     * Ensure channels exist and return a Notification ready to pass to
     * `Service.startForeground(NOTIFICATION_ID_DAEMON, ...)`.
     */
    public fun build(
        context: Context,
        statusText: CharSequence = "Speaker route active",
    ): Notification {
        NotificationChannelManager.ensureChannels(context)
        val tapTarget = appSettingsPendingIntent(context)
        val exportAction = buildExportLogsAction(context)
        return NotificationHelper.buildDaemonNotification(
            context = context,
            contentIntent = tapTarget,
            contentText = statusText,
            actions = listOf(exportAction),
        )
    }

    /** Notification id to pair with [build] in `startForeground`. */
    public const val NOTIFICATION_ID: Int = NotificationConstants.NOTIFICATION_ID_DAEMON

    /** Tap target: open the system Settings entry for this package. */
    public fun appSettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return IntentUtils.activityPendingIntent(
            context = context,
            requestCode = REQUEST_CODE_APP_SETTINGS,
            intent = intent,
        )
    }

    /**
     * Build the "Export logs" notification action — wired to
     * [LogExportReceiver]. Without ADB this is the user's only way to retrieve
     * diagnostics from a soak test.
     */
    public fun buildExportLogsAction(context: Context): Notification.Action {
        val intent = Intent(LogExportReceiver.ACTION_EXPORT_LOGS).apply {
            // Explicit component target so the broadcast lands even when the
            // OS restricts background implicit broadcasts (A8+).
            component = ComponentName(context, LogExportReceiver::class.java)
            `package` = context.packageName
        }
        val pending = IntentUtils.broadcastPendingIntent(
            context = context,
            requestCode = REQUEST_CODE_EXPORT_LOGS,
            intent = intent,
        )
        return Notification.Action.Builder(
            android.R.drawable.stat_sys_download_done,
            "Export logs",
            pending,
        ).build()
    }

    private const val REQUEST_CODE_APP_SETTINGS: Int = 0x42
    private const val REQUEST_CODE_EXPORT_LOGS: Int = 0x43
}

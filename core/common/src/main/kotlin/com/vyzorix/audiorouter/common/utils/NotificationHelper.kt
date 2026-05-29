// NotificationHelper — builds the daemon's foreground-service notification.
//
// Lives in core/common so service classes don't have to re-implement the
// build chain. Bound to the channel IDs in `constants/NotificationConstants`;
// callers must invoke `NotificationChannelManager.ensureChannels(...)` before
// posting (A13 enforces the channel-precondition or crashes).

package com.vyzorix.audiorouter.common.utils

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import com.vyzorix.audiorouter.common.constants.NotificationConstants

/** Builds the daemon's persistent foreground-service notification. */
public object NotificationHelper {

    /**
     * Build the canonical "daemon is running" notification.
     *
     * The notification is `setOngoing(true)` and `setOnlyAlertOnce(true)` so
     * it does not buzz on every state update; the only state changes that
     * notify the user are pushed through `CHANNEL_ALERT` separately.
     *
     * @param contentIntent The PendingIntent fired when the user taps the
     *   notification. Typically `Settings → app info` per the daemon's
     *   "no launcher" policy.
     * @param contentText Short status line (≤ 40 chars recommended). Defaults
     *   to a neutral "Vyzorix daemon active".
     */
    public fun buildDaemonNotification(
        context: Context,
        contentIntent: PendingIntent?,
        contentText: CharSequence = "Vyzorix daemon active",
    ): Notification {
        val builder = Notification.Builder(context, NotificationConstants.CHANNEL_DAEMON)
            .setContentTitle("Vyzorix")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        return builder.build()
    }

    /** Build a one-shot crash alert notification. */
    public fun buildCrashAlertNotification(
        context: Context,
        title: CharSequence,
        contentText: CharSequence,
        contentIntent: PendingIntent?,
    ): Notification {
        val builder = Notification.Builder(context, NotificationConstants.CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        return builder.build()
    }
}

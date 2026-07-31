// NotificationCompatBridge — cross-version helpers for the daemon's
// Notification objects.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 654:
//     core/services/compat/NotificationCompatBridge.kt
//       "Cross-version notification handling".
//
// Concerns covered:
//   1. CustomContentView / CustomBigContentView via
//      `setStyle(NotificationCompat.DecoratedCustomViewStyle())` for the
//      RemoteViews dashboard (NOTIFICATION_DASHBOARD.md §RemoteViews
//      Surface). The decorated style is mandatory on A12+ for the
//      "themed icon + custom collapsed view" combination.
//   2. Foreground-service notification builder — bridges between
//      NotificationCompat.Builder (preferred) and
//      Notification.Builder (used by the existing daemon notification
//      helper). The Layer 5 dashboard uses NotificationCompat for the
//      RemoteViews surface; we expose a single accessor so the wiring
//      doesn't fork.
//   3. setForegroundServiceBehavior on A12+ — when the service is
//      mediaProjection-typed we must declare it via
//      `FOREGROUND_SERVICE_DEFERRED` so the OS does not delay surfacing
//      the dashboard.
//
// Threading: all builders are synchronous and main-thread-safe.

package com.vyzorix.audiorouter.services.compat

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.vyzorix.audiorouter.common.constants.NotificationConstants

/**
 * Cross-version notification compat. Single object — all methods are
 * pure builders.
 */
public object NotificationCompatBridge {

    /**
     * Build the daemon's foreground-service notification using
     * NotificationCompat (the canonical Layer 5 surface).
     *
     * @param channelId notification channel ID; defaults to
     *   [NotificationConstants.CHANNEL_DAEMON].
     * @param smallIconRes drawable resource for the small icon.
     * @param collapsedView RemoteViews for the collapsed surface (always shown).
     * @param expandedView RemoteViews for the expanded surface (Tier 2/3 cards).
     * @param contentIntent tap target (defaults to null — the daemon's
     *   foreground notification has no tap target; actions handle UX).
     * @param actions list of [NotificationCompat.Action] for the
     *   notification button row (quick-toggle / restart / emergency-stop).
     */
    public fun buildDashboardNotification(
        context: Context,
        smallIconRes: Int,
        collapsedView: RemoteViews,
        expandedView: RemoteViews,
        channelId: String = NotificationConstants.CHANNEL_DAEMON,
        contentIntent: PendingIntent? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
        contentTitle: String? = null,
        contentText: String? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconRes)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setShowWhen(false)
        if (contentTitle != null) builder.setContentTitle(contentTitle)
        if (contentText != null) builder.setContentText(contentText)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        // Always set foreground service behavior since minSdk is 33 (S=31).
        builder.foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
        for (action in actions) {
            builder.addAction(action)
        }
        return builder.build()
    }

    /**
     * Build a simple `NotificationCompat.Action`. Delegates to
     * [PendingIntentCompatPolicy] for the flag selection so every
     * action across the daemon shares the same compat layer.
     */
    public fun buildAction(
        iconRes: Int,
        title: CharSequence,
        pendingIntent: PendingIntent,
    ): NotificationCompat.Action =
        NotificationCompat.Action.Builder(iconRes, title, pendingIntent)
            .setShowsUserInterface(false)
            .build()

    /**
     * True iff the platform supports `NotificationCompat.Builder.setStyle`
     * with `DecoratedCustomViewStyle` natively. Always true since minSdk is 33 (N=24).
     */
    public fun decoratedCustomViewSupported(): Boolean = true
}

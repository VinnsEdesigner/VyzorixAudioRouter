// FullScreenIntentBridge — wraps `ProjectionPermissionActivity` in a
// fullScreenIntent notification so the trampoline surfaces even on a
// locked screen.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 646:
//     core/services/projection/FullScreenIntentBridge.kt
//       "fullScreenIntent notification to surface permission dialog".
//
// Why this is needed:
//   On A14+ the OS refuses to launch an activity from a background
//   service unless one of several allow-listed conditions is met. The
//   most reliable is a notification with `setFullScreenIntent(...)` —
//   the OS treats the launch as "user-initiated via notification" even
//   when the user did not literally interact with the notification (the
//   notification is auto-cancelled after firing the intent).
//
// Per MEDIA_PROJECTION_FLOW.md §Mitigation 1: this bridge is the
// canonical fallback when [ProjectionLaunchCoordinator] determines the
// daemon cannot start the trampoline directly.
//
// We post the notification to a separate channel `CHANNEL_ALERT`
// (IMPORTANCE_HIGH) so the OS's "minimum-importance throttling" cannot
// degrade it to silent. The notification is auto-cancelled and its
// channel is intentionally NOT shared with the daemon foreground
// notification (the dashboard).

package com.vyzorix.audiorouter.services.projection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.services.capture.ProjectionPermissionContract
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [FullScreenIntentBridge.post]. */
public sealed interface FullScreenIntentPostResult {
    public object Posted : FullScreenIntentPostResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : FullScreenIntentPostResult
}

/** Diagnostic snapshot for the dashboard. */
public data class FullScreenIntentBridgeSnapshot(
    public val posts: Long,
    public val failures: Long,
    public val lastPostEpochMs: Long,
    public val lastResultLabel: String,
)

/**
 * Posts a fullScreenIntent notification that, when fired, launches the
 * canonical `ProjectionPermissionActivity` trampoline.
 *
 * Single-instance per coordinator. The bridge writes to its own channel
 * to avoid colliding with the dashboard's importance state.
 */
public class FullScreenIntentBridge(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val trampolineActivityClassName: String,
    private val channelId: String = NotificationConstants.CHANNEL_ALERT,
    private val notificationId: Int = NOTIFICATION_ID_REGRANT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    public constructor(
        context: Context,
        trampolineActivityClassName: String,
    ) : this(
        context = context,
        notificationManager = context.getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager,
        trampolineActivityClassName = trampolineActivityClassName,
    )

    private val posts: AtomicLong = AtomicLong(0L)
    private val failures: AtomicLong = AtomicLong(0L)
    private val lastPostEpochMs: AtomicLong = AtomicLong(0L)
    private val lastResultLabel: AtomicReference<String> = AtomicReference("init")

    /**
     * Post the notification. [triggerOrigin] is forwarded into the
     * activity intent so the broadcast carries an origin label.
     */
    public fun post(triggerOrigin: String): FullScreenIntentPostResult {
        ensureChannel()
        val activityIntent = Intent().apply {
            setClassName(context.packageName, trampolineActivityClassName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN, triggerOrigin)
        }
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = try {
            android.app.Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_TEXT)
                .setContentIntent(fullScreenIntent)
                .setFullScreenIntent(fullScreenIntent, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .setCategory(android.app.Notification.CATEGORY_CALL)
                .build()
        } catch (t: Throwable) {
            failures.incrementAndGet()
            lastResultLabel.set("build_threw")
            DaemonLogger.get().error(
                TAG,
                "bridge.post.build_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return FullScreenIntentPostResult.Failed("notification_build_threw", t)
        }
        return try {
            notificationManager.notify(notificationId, notification)
            posts.incrementAndGet()
            lastPostEpochMs.set(clock())
            lastResultLabel.set("posted")
            DaemonLogger.get().info(
                TAG,
                "bridge.post.success origin=$triggerOrigin total=${posts.get()}",
            )
            FullScreenIntentPostResult.Posted
        } catch (t: Throwable) {
            failures.incrementAndGet()
            lastResultLabel.set("notify_threw")
            DaemonLogger.get().error(
                TAG,
                "bridge.post.notify_threw err=${t.javaClass.simpleName}",
            )
            FullScreenIntentPostResult.Failed("notification_notify_threw", t)
        }
    }

    /** Cancel the bridge notification (called after projection result). */
    public fun cancel() {
        try {
            notificationManager.cancel(notificationId)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "bridge.cancel.threw err=${t.javaClass.simpleName}",
            )
        }
    }

    /** Diagnostic snapshot. */
    public fun snapshot(): FullScreenIntentBridgeSnapshot =
        FullScreenIntentBridgeSnapshot(
            posts = posts.get(),
            failures = failures.get(),
            lastPostEpochMs = lastPostEpochMs.get(),
            lastResultLabel = lastResultLabel.get(),
        )

    private fun ensureChannel() {
        val existing = try {
            notificationManager.getNotificationChannel(channelId)
        } catch (_: Throwable) {
            null
        }
        if (existing != null) return
        val channel = NotificationChannel(
            channelId,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setBypassDnd(false)
            enableLights(true)
            enableVibration(false)
        }
        try {
            notificationManager.createNotificationChannel(channel)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "bridge.channel.create_threw err=${t.javaClass.simpleName}",
            )
        }
    }

    public companion object {
        public const val NOTIFICATION_ID_REGRANT: Int = 1010
        public const val REQUEST_CODE: Int = 0x517A1A
        public const val NOTIFICATION_TITLE: String = "Vyzorix · Re-authorise screen capture"
        public const val NOTIFICATION_TEXT: String =
            "Tap to renew the audio capture permission. The daemon is paused until granted."
        public const val CHANNEL_NAME: String = "Vyzorix re-authorise prompts"
        public const val CHANNEL_DESCRIPTION: String =
            "Surfaces full-screen prompts when the daemon needs to renew its screen-capture permission."
        private const val TAG: String = "FullScreenIntentBridge"
    }
}

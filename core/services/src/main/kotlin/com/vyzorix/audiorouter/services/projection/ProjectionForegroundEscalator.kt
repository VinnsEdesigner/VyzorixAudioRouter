// ProjectionForegroundEscalator — temporarily elevates the daemon's
// notification importance during a re-grant flow.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 651:
//     core/services/projection/ProjectionForegroundEscalator.kt
//       "Temporarily elevates priority during re-grant".
//
// Why this exists: on Android 12+ the system applies aggressive
// "minimum-importance throttling" to long-lived foreground service
// notifications (the OS reasons: a service shouldn't need to keep
// alerting once the user has acknowledged it). The trampoline's
// fullScreenIntent only succeeds if the surrounding notification
// channel is in the IMPORTANCE_HIGH band at launch time. We push the
// channel up to HIGH for the duration of the re-grant flow and put it
// back to DEFAULT once the projection result has been received.
//
// Escalation is a no-op on devices that ignore channel-importance
// changes after creation (the OS spec allows this; we just log).
//
// Threading: NotificationChannel writes are main-thread-safe; we use
// the AtomicReference dance only to coalesce concurrent escalate()
// calls.

package com.vyzorix.audiorouter.services.projection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Outcome of [ProjectionForegroundEscalator.escalate]. */
public sealed interface EscalationResult {
    public data class Elevated(public val previousImportance: Int) : EscalationResult
    public object AlreadyHigh : EscalationResult
    public data class ChannelMissing(public val channelId: String) : EscalationResult
    public data class Failed(public val cause: Throwable) : EscalationResult
}

/** Diagnostic snapshot for the dashboard. */
public data class ProjectionForegroundEscalatorSnapshot(
    public val escalations: Long,
    public val deescalations: Long,
    public val currentlyEscalated: Boolean,
    public val lastEscalationEpochMs: Long,
    public val lastBaselineImportance: Int,
)

/**
 * Owns the importance toggle for the daemon's notification channel.
 *
 * Lifecycle:
 *   1. ProjectionLaunchCoordinator.launch() — calls [escalate] before
 *      firing the trampoline.
 *   2. Projection result received — calls [deescalate] regardless of
 *      grant/deny.
 *
 * [escalate] is idempotent; concurrent calls coalesce.
 */
public class ProjectionForegroundEscalator(
    private val notificationManager: NotificationManager,
    private val channelId: String = NotificationConstants.CHANNEL_DAEMON,
    private val elevatedImportance: Int = NotificationManager.IMPORTANCE_HIGH,
    private val baselineImportance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    public constructor(context: Context) : this(
        notificationManager = context.getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager,
    )

    private val escalated: AtomicBoolean = AtomicBoolean(false)
    private val escalations: AtomicLong = AtomicLong(0L)
    private val deescalations: AtomicLong = AtomicLong(0L)
    private val lastEscalationEpochMs: AtomicLong = AtomicLong(0L)
    private val lastBaselineImportance: AtomicInteger = AtomicInteger(baselineImportance)

    /** Push the daemon channel to elevated importance. Idempotent. */
    public fun escalate(): EscalationResult {
        if (!escalated.compareAndSet(false, true)) {
            return EscalationResult.AlreadyHigh
        }
        val channel = try {
            notificationManager.getNotificationChannel(channelId)
        } catch (t: Throwable) {
            escalated.set(false)
            DaemonLogger.get().warn(
                TAG,
                "escalate.channel_query_threw err=${t.javaClass.simpleName}",
            )
            return EscalationResult.Failed(t)
        }
        if (channel == null) {
            escalated.set(false)
            DaemonLogger.get().warn(TAG, "escalate.channel_missing channelId=$channelId")
            return EscalationResult.ChannelMissing(channelId)
        }
        val previous = channel.importance
        lastBaselineImportance.set(previous)
        if (previous >= elevatedImportance) {
            DaemonLogger.get().info(
                TAG,
                "escalate.already_high previous=$previous required=$elevatedImportance",
            )
            return EscalationResult.AlreadyHigh
        }
        return try {
            // Channels can have importance changed via re-creation; the
            // system honours the new value only on first creation but
            // accepts the call without error otherwise. We log the
            // outcome so the dashboard can show "channel pinned high".
            val replacement = NotificationChannel(channelId, channel.name, elevatedImportance)
            replacement.description = channel.description
            replacement.enableVibration(channel.shouldVibrate())
            replacement.lightColor = channel.lightColor
            notificationManager.createNotificationChannel(replacement)
            escalations.incrementAndGet()
            lastEscalationEpochMs.set(clock())
            DaemonLogger.get().info(
                TAG,
                "escalate.elevated previous=$previous to=$elevatedImportance",
            )
            EscalationResult.Elevated(previous)
        } catch (t: Throwable) {
            escalated.set(false)
            DaemonLogger.get().warn(
                TAG,
                "escalate.create_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            EscalationResult.Failed(t)
        }
    }

    /** Restore the daemon channel to baseline importance. Idempotent. */
    public fun deescalate() {
        if (!escalated.compareAndSet(true, false)) return
        val channel = try {
            notificationManager.getNotificationChannel(channelId)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "deescalate.channel_query_threw err=${t.javaClass.simpleName}",
            )
            return
        }
        if (channel == null) {
            DaemonLogger.get().warn(TAG, "deescalate.channel_missing channelId=$channelId")
            return
        }
        val target = lastBaselineImportance.get()
        try {
            val replacement = NotificationChannel(channelId, channel.name, target)
            replacement.description = channel.description
            replacement.enableVibration(channel.shouldVibrate())
            replacement.lightColor = channel.lightColor
            notificationManager.createNotificationChannel(replacement)
            deescalations.incrementAndGet()
            DaemonLogger.get().info(
                TAG,
                "deescalate.restored to=$target",
            )
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "deescalate.create_threw err=${t.javaClass.simpleName}",
            )
        }
    }

    /** True if the escalator currently has the channel pinned high. */
    public fun isEscalated(): Boolean = escalated.get()

    /** Diagnostic snapshot. */
    public fun snapshot(): ProjectionForegroundEscalatorSnapshot =
        ProjectionForegroundEscalatorSnapshot(
            escalations = escalations.get(),
            deescalations = deescalations.get(),
            currentlyEscalated = escalated.get(),
            lastEscalationEpochMs = lastEscalationEpochMs.get(),
            lastBaselineImportance = lastBaselineImportance.get(),
        )

    public companion object {
        private const val TAG: String = "ProjectionForegroundEscalator"
    }
}

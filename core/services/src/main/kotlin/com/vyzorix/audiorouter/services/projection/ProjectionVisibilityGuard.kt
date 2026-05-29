// ProjectionVisibilityGuard — aborts a projection launch if the daemon's
// foreground-service notification is missing.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 650:
//     core/services/projection/ProjectionVisibilityGuard.kt
//       "Aborts if foreground eligibility missing".
//
// Background (DOC_3 §6.4): Android A14 introduced a strict requirement
// that an app holding the `mediaProjection` foreground-service-type
// MUST already have a visible foreground-service notification at the
// moment `MediaProjection.createVirtualDisplay` /
// `AudioPlaybackCaptureConfiguration` is consumed. If the daemon's
// notification has been swiped/dismissed by the OS while the trampoline
// was alive, the projection start will throw SecurityException with the
// message "Media projections require a foreground service of type
// FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION".
//
// The guard checks `NotificationManager.getActiveNotifications()` for
// the daemon notification ID before we begin. If absent we refuse to
// launch and instead emit a [Reason.NotificationGone] signal so the
// caller (CaptureLifecycleController) can re-post the notification
// before retrying.

package com.vyzorix.audiorouter.services.projection

import android.app.NotificationManager
import android.content.Context
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [ProjectionVisibilityGuard.check]. */
public sealed interface VisibilityCheckResult {
    public object Visible : VisibilityCheckResult
    public data class NotificationGone(public val reason: String) : VisibilityCheckResult
}

/** Diagnostic snapshot for the dashboard. */
public data class ProjectionVisibilityGuardSnapshot(
    public val checks: Long,
    public val visibleCount: Long,
    public val notificationGoneCount: Long,
    public val lastCheckEpochMs: Long,
    public val lastResultLabel: String,
)

/**
 * Single-instance, owned by [ProjectionLaunchCoordinator]. All methods
 * are stateless reads against the system NotificationManager except for
 * counter bookkeeping (atomic).
 */
public class ProjectionVisibilityGuard(
    private val notificationManager: NotificationManager,
    private val daemonNotificationId: Int = NotificationConstants.NOTIFICATION_ID_DAEMON,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    public constructor(context: Context) : this(
        notificationManager = context.getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager,
    )

    private val checks: AtomicLong = AtomicLong(0L)
    private val visibleCount: AtomicLong = AtomicLong(0L)
    private val notificationGoneCount: AtomicLong = AtomicLong(0L)
    private val lastCheckEpochMs: AtomicLong = AtomicLong(0L)
    private val lastResultLabel: AtomicReference<String> = AtomicReference("init")

    /** Run the check. Safe to call from any thread. */
    public fun check(): VisibilityCheckResult {
        checks.incrementAndGet()
        lastCheckEpochMs.set(clock())
        val active = try {
            notificationManager.activeNotifications
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "visibility.query_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            // Treat a thrown query as visible — we don't want to refuse
            // a launch because the NotificationManager hiccuped.
            visibleCount.incrementAndGet()
            lastResultLabel.set("visible_query_threw")
            return VisibilityCheckResult.Visible
        }
        val found = active.any { it.id == daemonNotificationId }
        return if (found) {
            visibleCount.incrementAndGet()
            lastResultLabel.set("visible")
            VisibilityCheckResult.Visible
        } else {
            notificationGoneCount.incrementAndGet()
            lastResultLabel.set("notification_gone")
            DaemonLogger.get().warn(
                TAG,
                "visibility.notification_gone daemon_id=$daemonNotificationId activeCount=${active.size}",
            )
            VisibilityCheckResult.NotificationGone("daemon_notification_absent")
        }
    }

    /** Diagnostic snapshot. */
    public fun snapshot(): ProjectionVisibilityGuardSnapshot =
        ProjectionVisibilityGuardSnapshot(
            checks = checks.get(),
            visibleCount = visibleCount.get(),
            notificationGoneCount = notificationGoneCount.get(),
            lastCheckEpochMs = lastCheckEpochMs.get(),
            lastResultLabel = lastResultLabel.get(),
        )

    public companion object {
        private const val TAG: String = "ProjectionVisibilityGuard"
    }
}

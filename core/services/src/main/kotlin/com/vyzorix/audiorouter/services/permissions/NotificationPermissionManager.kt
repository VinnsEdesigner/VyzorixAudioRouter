// NotificationPermissionManager — A13+ `POST_NOTIFICATIONS` runtime
// permission gating.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 660:
//     core/services/permissions/NotificationPermissionManager.kt
//       "A13 POST_NOTIFICATIONS".
//
// Background: Android 13 (API 33) introduced
// `POST_NOTIFICATIONS` as a runtime permission. Without it the daemon's
// foreground-service notification (the Layer 5 dashboard) is silently
// dropped — the service stays alive but the user has no surface to
// interact with it. Worse, MediaProjection's fullScreenIntent surface
// (Layer 4 re-grant flow) is also gated on this permission.
//
// On A13+ devices the BootstrapActivity surfaces the runtime request
// during onboarding. On post-bootstrap launches we re-check via
// [isGranted] and surface a fullScreenIntent prompt if the user
// revoked the grant.
//
// Pre-A13 the permission is implicitly granted; we no-op the checks.
//
// Threading: all queries are synchronous and main-thread-safe.

package com.vyzorix.audiorouter.services.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong

/** Outcome of [NotificationPermissionManager.evaluate]. */
public sealed interface NotificationPermissionState {
    public object Granted : NotificationPermissionState
    public object DeniedRevocable : NotificationPermissionState
    public object DeniedPermanent : NotificationPermissionState
    public object NotApplicable : NotificationPermissionState
}

/** Diagnostic snapshot for the dashboard. */
public data class NotificationPermissionSnapshot(
    public val evaluations: Long,
    public val grantedCount: Long,
    public val deniedCount: Long,
    public val lastEvaluationEpochMs: Long,
    public val lastResultLabel: String,
)

/**
 * Stateless evaluator (counters live for the dashboard). Single-instance
 * per service.
 */
public class NotificationPermissionManager(
    private val context: Context,
    private val notificationManager: NotificationManager = context.getSystemService(
        Context.NOTIFICATION_SERVICE,
    ) as NotificationManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val evaluations: AtomicLong = AtomicLong(0L)
    private val grantedCount: AtomicLong = AtomicLong(0L)
    private val deniedCount: AtomicLong = AtomicLong(0L)
    private val lastEvaluationEpochMs: AtomicLong = AtomicLong(0L)
    @Volatile private var lastResultLabel: String = "init"

    /** True iff the daemon currently has the `POST_NOTIFICATIONS` grant. */
    public fun isGranted(): Boolean {
        // Always check POST_NOTIFICATIONS permission since minSdk is 33 (TIRAMISU=33).
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "permission.is_granted.threw err=${t.javaClass.simpleName}",
            )
            false
        }
    }

    /**
     * True iff the OS reports notifications are enabled for the app
     * (this is a superset of the runtime permission — a user can
     * disable notifications via long-press without revoking the
     * permission).
     */
    public fun notificationsEnabled(): Boolean {
        return try {
            notificationManager.areNotificationsEnabled()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "permission.notifications_enabled.threw err=${t.javaClass.simpleName}",
            )
            false
        }
    }

    /**
     * Full evaluation. Caller uses the result to decide between
     * `BootstrapActivity.requestPermission()` and the
     * fullScreenIntent escalation.
     */
    public fun evaluate(): NotificationPermissionState {
        evaluations.incrementAndGet()
        lastEvaluationEpochMs.set(clock())
        // Always evaluate POST_NOTIFICATIONS since minSdk is 33 (TIRAMISU=33).
        val granted = isGranted()
        if (granted) {
            grantedCount.incrementAndGet()
            lastResultLabel = "granted"
            return NotificationPermissionState.Granted
        }
        deniedCount.incrementAndGet()
        // We can't distinguish "user hasn't been asked yet" from
        // "user denied twice (permanent)" from a non-Activity context
        // — that requires `shouldShowRequestPermissionRationale` which
        // is Activity-bound. The Activity-bound surface lives in
        // BootstrapActivity (Layer 3). Here we conservatively report
        // revocable so the daemon retries the prompt.
        lastResultLabel = "denied_revocable"
        DaemonLogger.get().warn(TAG, "permission.evaluate.denied")
        return NotificationPermissionState.DeniedRevocable
    }

    /** Diagnostic snapshot. */
    public fun snapshot(): NotificationPermissionSnapshot =
        NotificationPermissionSnapshot(
            evaluations = evaluations.get(),
            grantedCount = grantedCount.get(),
            deniedCount = deniedCount.get(),
            lastEvaluationEpochMs = lastEvaluationEpochMs.get(),
            lastResultLabel = lastResultLabel,
        )

    public companion object {
        private const val TAG: String = "NotificationPermissionManager"
    }
}

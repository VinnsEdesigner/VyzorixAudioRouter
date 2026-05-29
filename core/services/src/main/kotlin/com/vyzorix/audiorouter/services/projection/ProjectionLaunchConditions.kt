// ProjectionLaunchConditions — preflight checks that must pass before
// `ProjectionPermissionActivity` is launched.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 648:
//     core/services/projection/ProjectionLaunchConditions.kt
//       "Screen unlocked + notification channel active checks".
//
// Why a separate class:
//   Launching the trampoline against a locked screen produces a one-off
//   UX glitch (the MediaProjection dialog appears under the lockscreen)
//   AND a forensic mess (the activity-result return path is racy because
//   the Activity is paused while locked). We refuse to even try and
//   instead wait for the screen to be unlocked. Same logic for missing
//   notification channels — Android 13+ refuses to surface fullScreenIntent
//   notifications without an active channel and the user has no recourse
//   except killing the daemon.
//
// All four canonical checks (per RepoTree §projection):
//   1. Screen is interactive (PowerManager.isInteractive).
//   2. Keyguard is NOT locked (KeyguardManager.isKeyguardLocked).
//   3. Notification channel CHANNEL_DAEMON exists and is not blocked.
//   4. POST_NOTIFICATIONS permission granted (A13+) — without it
//      FullScreenIntentBridge can't surface the consent dialog.
//
// Threading: all queries are synchronous and main-thread-safe.

package com.vyzorix.audiorouter.services.projection

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Outcome of [ProjectionLaunchConditions.evaluate]. */
public sealed interface ProjectionLaunchCondition {

    /** Every precondition is satisfied — caller may launch. */
    public object Ready : ProjectionLaunchCondition

    /** A precondition is failing. [labels] enumerates each violated check. */
    public data class Blocked(public val labels: List<String>) : ProjectionLaunchCondition {
        public val firstReason: String get() = labels.firstOrNull() ?: "unknown"
    }
}

/** Diagnostic snapshot of the last evaluation. */
public data class ProjectionLaunchConditionsSnapshot(
    public val lastEvaluationEpochMs: Long,
    public val lastResultLabel: String,
    public val lastFailureLabels: List<String>,
)

/**
 * Stateless evaluator (cached counters live for the dashboard). The
 * caller invokes [evaluate] right before firing
 * `ProjectionPermissionActivity.intent(...)` and gates the launch on the
 * returned condition.
 */
public class ProjectionLaunchConditions(
    private val context: Context,
    private val powerManager: PowerManager = context.getSystemService(
        Context.POWER_SERVICE,
    ) as PowerManager,
    private val keyguardManager: KeyguardManager = context.getSystemService(
        Context.KEYGUARD_SERVICE,
    ) as KeyguardManager,
    private val notificationManager: NotificationManager = context.getSystemService(
        Context.NOTIFICATION_SERVICE,
    ) as NotificationManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    @Volatile private var lastEvaluationEpochMs: Long = 0L
    @Volatile private var lastResultLabel: String = "init"
    @Volatile private var lastFailureLabels: List<String> = emptyList()

    /** Run all four checks and return a single decision. */
    public fun evaluate(): ProjectionLaunchCondition {
        val failures = mutableListOf<String>()
        if (!powerManager.isInteractive) {
            failures += LABEL_SCREEN_OFF
        }
        if (keyguardManager.isKeyguardLocked) {
            failures += LABEL_KEYGUARD_LOCKED
        }
        val channelOk = isDaemonChannelActive()
        if (!channelOk) {
            failures += LABEL_CHANNEL_INACTIVE
        }
        if (!postNotificationsGranted()) {
            failures += LABEL_NOTIFICATIONS_DENIED
        }
        lastEvaluationEpochMs = clock()
        lastFailureLabels = failures.toList()
        return if (failures.isEmpty()) {
            lastResultLabel = "ready"
            ProjectionLaunchCondition.Ready
        } else {
            lastResultLabel = "blocked"
            DaemonLogger.get().info(
                TAG,
                "launch_conditions.blocked failures=${failures.joinToString(",")}",
            )
            ProjectionLaunchCondition.Blocked(failures.toList())
        }
    }

    /** Diagnostic snapshot for the dashboard. */
    public fun snapshot(): ProjectionLaunchConditionsSnapshot =
        ProjectionLaunchConditionsSnapshot(
            lastEvaluationEpochMs = lastEvaluationEpochMs,
            lastResultLabel = lastResultLabel,
            lastFailureLabels = lastFailureLabels,
        )

    private fun isDaemonChannelActive(): Boolean {
        val channel = try {
            notificationManager.getNotificationChannel(NotificationConstants.CHANNEL_DAEMON)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "launch_conditions.channel_query_threw err=${t.javaClass.simpleName}",
            )
            return false
        }
        if (channel == null) return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun postNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS was introduced in A13. Pre-A13 the
            // implicit grant is always present.
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.POST_NOTIFICATIONS",
        ) == PackageManager.PERMISSION_GRANTED
    }

    public companion object {
        public const val LABEL_SCREEN_OFF: String = "screen_off"
        public const val LABEL_KEYGUARD_LOCKED: String = "keyguard_locked"
        public const val LABEL_CHANNEL_INACTIVE: String = "channel_inactive"
        public const val LABEL_NOTIFICATIONS_DENIED: String = "notifications_denied"
        private const val TAG: String = "ProjectionLaunchConditions"
    }
}

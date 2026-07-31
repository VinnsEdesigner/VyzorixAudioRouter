// NotificationTrampolineCompat — encapsulates the Android 12+ ban on
// notification "trampolines" (notification-broadcast-receiver →
// activity-launch).
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 655:
//     core/services/compat/NotificationTrampolineCompat.kt
//       "Android 12+ notification trampoline rules".
//
// Per the Android 12 behaviour changes (per the official docs and
// MEDIA_PROJECTION_FLOW.md §Trampoline-Killer Workaround), a
// BroadcastReceiver fired from a notification action MAY NOT start a
// foreground activity on its own — the OS detects the chained launch
// and drops the activity start. The daemon uses two valid patterns:
//
//   1. Pre-A12: notification → BroadcastReceiver → startActivity is fine.
//   2. A12+: notification action → PendingIntent.getActivity directly,
//      OR notification action → BroadcastReceiver that posts a
//      fullScreenIntent and lets the system surface the activity.
//
// This object exposes the canonical decision so call sites read like:
//
//   if (NotificationTrampolineCompat.canTrampolineFromBroadcast()) {
//       pendingIntent.getBroadcast(...)   // pre-A12 fast path
//   } else {
//       pendingIntent.getActivity(...)    // A12+ direct
//   }
//
// We also include helpers for the "post a fullScreenIntent from a
// broadcast" path that the EmergencyStopAction / RestartPipelineAction
// rely on.

package com.vyzorix.audiorouter.services.compat

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/** Stateless compat policy. */
public object NotificationTrampolineCompat {

    /**
     * True iff a notification-action BroadcastReceiver may start a
     * foreground activity directly via `startActivity`. False on
     * Android 12+.
     *
     * Always false since minSdk is 33 (S=31).
     */
    public fun canTrampolineFromBroadcast(): Boolean = false

    /**
     * True iff the OS supports fullScreenIntent fallback. fullScreenIntent
     * has been available since A10 (API 29).
     *
     * Always true since minSdk is 33 (Q=29).
     */
    public fun fullScreenIntentSupported(): Boolean = true

    /**
     * True iff the platform requires the
     * `USE_FULL_SCREEN_INTENT` runtime permission for fullScreenIntent
     * notifications. Introduced in A14 (API 34, U).
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public fun fullScreenIntentRequiresPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}

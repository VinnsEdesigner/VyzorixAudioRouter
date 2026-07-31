// PendingIntentCompatPolicy — canonical flag selection for PendingIntents
// the daemon constructs.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 656:
//     core/services/compat/PendingIntentCompatPolicy.kt
//       "PendingIntent compat for A12+".
//
// Background: A12 (API 31) made PendingIntent mutability flags mandatory
// — every PendingIntent created on A12+ MUST be flagged FLAG_IMMUTABLE
// or FLAG_MUTABLE; an unflagged PendingIntent throws IllegalArgumentException
// at runtime. The daemon uses several patterns:
//
//   1. Notification action receivers — IMMUTABLE (we own the intent).
//   2. Notification content intents — IMMUTABLE.
//   3. FullScreenIntent (re-grant) — IMMUTABLE (the activity reads
//      extras from the intent at receive time; mutability is not
//      required for that).
//   4. AlarmManager-style retry intents — IMMUTABLE.
//
// We codify these decisions in one place so every PendingIntent ctor in
// the daemon flows through `PendingIntentCompatPolicy.broadcastFlags()`
// / `activityFlags()` rather than handwriting the flag bits. This keeps
// the surface area searchable and audit-friendly.

package com.vyzorix.audiorouter.services.compat

import android.app.PendingIntent
import android.os.Build

/** Stateless flag-selection policy for the daemon's PendingIntents. */
public object PendingIntentCompatPolicy {

    /** Flags for `PendingIntent.getBroadcast(...)` calls. */
    public fun broadcastFlags(updateCurrent: Boolean = true): Int =
        baseFlag(updateCurrent) or immutableFlag()

    /** Flags for `PendingIntent.getActivity(...)` calls. */
    public fun activityFlags(updateCurrent: Boolean = true): Int =
        baseFlag(updateCurrent) or immutableFlag()

    /** Flags for `PendingIntent.getService(...)` calls. */
    public fun serviceFlags(updateCurrent: Boolean = true): Int =
        baseFlag(updateCurrent) or immutableFlag()

    /**
     * Flags for cases that genuinely require mutability — fill-in
     * Intent extras delivered by the OS (e.g. WidgetManager click
     * pendingIntents that come pre-attached to a remote view). The
     * daemon should use this sparingly; auditing tools will flag any
     * call site referencing this method.
     */
    public fun mutableBroadcastFlags(updateCurrent: Boolean = true): Int =
        baseFlag(updateCurrent) or mutableFlag()

    private fun baseFlag(updateCurrent: Boolean): Int =
        if (updateCurrent) PendingIntent.FLAG_UPDATE_CURRENT else 0

    private fun immutableFlag(): Int =
        // Always set FLAG_IMMUTABLE since minSdk is 33 (M=23).
        PendingIntent.FLAG_IMMUTABLE

    private fun mutableFlag(): Int =
        // Always set FLAG_MUTABLE since minSdk is 33 (S=31).
        PendingIntent.FLAG_MUTABLE
}

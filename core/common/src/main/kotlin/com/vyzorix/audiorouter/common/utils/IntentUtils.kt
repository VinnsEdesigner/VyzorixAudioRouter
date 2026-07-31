// IntentUtils — helpers for safely building Intents and PendingIntents.
//
// Centralised here so the daemon never builds a PendingIntent without the
// correct mutability flag (an A12+ `IllegalArgumentException` is a hard
// crash on the C22).

package com.vyzorix.audiorouter.common.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Helpers for safe Intent construction. */
public object IntentUtils {

    /**
     * Build a `PendingIntent` for [Context.startActivity] that complies with
     * the A12+ mandatory mutability flag requirement.
     *
     * @param mutable If `true`, callers can update the Intent's extras. Default
     *   is `false` (immutable) — only set true if the receiver needs to attach
     *   `RemoteInput` extras.
     */
    public fun activityPendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
        mutable: Boolean = false,
    ): PendingIntent {
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT
        val mutabilityFlag = mutabilityFlag(mutable)
        return PendingIntent.getActivity(context, requestCode, intent, baseFlags or mutabilityFlag)
    }

    /** Same as [activityPendingIntent] but for [Context.startService]. */
    public fun servicePendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
        mutable: Boolean = false,
    ): PendingIntent {
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT
        val mutabilityFlag = mutabilityFlag(mutable)
        return PendingIntent.getService(context, requestCode, intent, baseFlags or mutabilityFlag)
    }

    /** Same as [activityPendingIntent] but for [Context.sendBroadcast]. */
    public fun broadcastPendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
        mutable: Boolean = false,
    ): PendingIntent {
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT
        val mutabilityFlag = mutabilityFlag(mutable)
        return PendingIntent.getBroadcast(context, requestCode, intent, baseFlags or mutabilityFlag)
    }

    /**
     * Returns `PendingIntent.FLAG_IMMUTABLE` or `FLAG_MUTABLE` depending on
     * [mutable]. Both flags are guaranteed present since minSdk is 33 (S=31).
     */
    public fun mutabilityFlag(mutable: Boolean): Int {
        return if (mutable) {
            // Always set FLAG_MUTABLE since minSdk is 33 (S=31).
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
    }

    /**
     * Render an Intent into a short, log-safe summary. Filters out potentially
     * sensitive extra payloads — only the action, component, and category set
     * are emitted.
     */
    public fun describeForLog(intent: Intent?): String {
        if (intent == null) return "Intent(null)"
        val action = intent.action ?: "<none>"
        val component = intent.component?.flattenToShortString() ?: "<none>"
        val categories = intent.categories?.joinToString(",") ?: "<none>"
        return "Intent(action=$action, component=$component, categories=$categories)"
    }
}

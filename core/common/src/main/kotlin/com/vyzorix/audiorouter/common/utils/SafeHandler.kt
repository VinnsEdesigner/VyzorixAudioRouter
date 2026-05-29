// SafeHandler — wraps `android.os.Handler` so that exceptions thrown by a
// posted Runnable are caught and reported instead of crashing the Looper.
//
// Use sites are anywhere the daemon hands a Runnable to a Handler that
// outlives the immediate scope (NotificationActions, accessibility callbacks,
// MediaSession callbacks, etc.). The Layer 6+ crash pipeline relies on
// `unhandledExceptionReporter` to surface what would otherwise be a silent
// Looper death.

package com.vyzorix.audiorouter.common.utils

import android.os.Handler
import android.os.Looper

/**
 * Exception-safe wrapper around a [Handler].
 *
 * Every [post] / [postDelayed] / [postAtTime] catches [Throwable] from the
 * wrapped Runnable and forwards it to [unhandledExceptionReporter]. The
 * Looper continues running.
 */
public class SafeHandler(
    private val handler: Handler,
    private val unhandledExceptionReporter: (Throwable) -> Unit,
) {

    public constructor(
        looper: Looper,
        unhandledExceptionReporter: (Throwable) -> Unit,
    ) : this(Handler(looper), unhandledExceptionReporter)

    public fun post(action: () -> Unit): Boolean =
        handler.post(wrap(action))

    public fun postDelayed(delayMillis: Long, action: () -> Unit): Boolean =
        handler.postDelayed(wrap(action), delayMillis)

    public fun postAtTime(uptimeMillis: Long, action: () -> Unit): Boolean =
        handler.postAtTime(wrap(action), uptimeMillis)

    public fun removeCallbacksAndMessages(token: Any? = null) {
        handler.removeCallbacksAndMessages(token)
    }

    private fun wrap(action: () -> Unit): Runnable = Runnable {
        try {
            action()
        } catch (t: Throwable) {
            try {
                unhandledExceptionReporter(t)
            } catch (_: Throwable) {
                // The reporter must never propagate — that would re-crash the Looper.
            }
        }
    }
}

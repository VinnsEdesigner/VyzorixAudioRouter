// WakeLockGuard — owns the daemon's PARTIAL_WAKE_LOCK.
//
// Why we need a wake lock in addition to a foreground service:
//   - Foreground service + mediaPlayback type keeps the OS scheduler from
//     killing the process for "no foreground component" reasons, but it
//     does NOT prevent CPU sleep when the screen is off and no audio is
//     actively flowing.
//   - The silent VoIP anchor at -90 dBFS is silent enough that some HALs
//     suspend the audio block, after which Doze can suspend the CPU.
//   - The 500 ms SpeakerForceEngine tick must run within ~50 ms of its
//     scheduled time for the route war to keep up with HAL drift. CPU sleep
//     ruins this guarantee.
//
// API: acquire() on engage, release() on disengage. The class is
// idempotent — repeated acquires don't stack reference counts.

package com.vyzorix.audiorouter.services.managers

import android.content.Context
import android.os.PowerManager

/** Thread-safe wrapper around a single `PARTIAL_WAKE_LOCK`. */
public class WakeLockGuard(
    context: Context,
    private val tag: String = DEFAULT_TAG,
    powerManager: PowerManager? = null,
) {

    private val resolvedPowerManager: PowerManager? = powerManager
        ?: context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val lock: PowerManager.WakeLock? =
        resolvedPowerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
            // setReferenceCounted(false) so repeated acquire()/release() pairs
            // can't leave the lock dangling. Single-shot semantics is what we
            // want: "the engine is engaged, hold the lock".
            setReferenceCounted(false)
        }

    /** Returns whether the lock is currently held. */
    public fun isHeld(): Boolean = lock?.isHeld == true

    /**
     * Acquire the lock. Idempotent. Returns `true` if the lock is held
     * after the call (regardless of whether this call was the one that
     * acquired it).
     */
    public fun acquire(): Boolean {
        val l = lock ?: return false
        if (!l.isHeld) {
            // Timeout of 10 minutes — safety net in case release() is missed.
            // The daemon normally releases on disengage(), but the timeout
            // ensures the OS can clean up if that never happens.
            l.acquire(WAKELOCK_TIMEOUT_MS)
        }
        return l.isHeld
    }

    /** Release the lock if held. Idempotent. */
    public fun release() {
        val l = lock ?: return
        if (l.isHeld) {
            // releaseSafely — if the OS already released it under us (rare,
            // but happens in some thermal-throttle paths) we don't crash.
            try {
                l.release()
            } catch (_: RuntimeException) {
                // Already released by the system.
            }
        }
    }

    public companion object {
        public const val DEFAULT_TAG: String = "Vyzorix:SpeakerForce"
        /** 10-minute timeout for the wake lock — safety net, not the primary release mechanism. */
        private const val WAKELOCK_TIMEOUT_MS: Long = 10 * 60 * 1000
    }
}

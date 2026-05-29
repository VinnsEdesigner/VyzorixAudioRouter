// ProjectionGrantCache — in-memory cache of the last MediaProjection
// grant outcome, with a short TTL.
//
// Why a cache (vs reading the persisted store every time): BootStateRestorer
// + ServiceTrampoline need to make a fast "do we have an active grant?"
// decision without waiting on DataStore I/O. The cache fills on token
// grant + invalidates on revoke. TTL is short enough that staleness on
// process restart is irrelevant (the persisted store still wins for
// authoritative state).
//
// Per doc/BUILD_ORDER.md §Layer 4 ("ProjectionGrantCache").

package com.vyzorix.audiorouter.services.permissions

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference

/** Cached snapshot returned by [ProjectionGrantCache.snapshot]. */
public data class ProjectionGrantSnapshot(
    public val granted: Boolean,
    public val grantedAtEpochMs: Long?,
    public val triggerOrigin: String?,
    public val sampleRateHz: Int?,
    public val channelCount: Int?,
    public val cachedAtEpochMs: Long,
    public val ttlMs: Long,
) {
    public fun isFresh(nowEpochMs: Long): Boolean = (nowEpochMs - cachedAtEpochMs) < ttlMs
}

/**
 * In-memory cache for MediaProjection grant outcomes. Thread-safe via
 * `AtomicReference`.
 */
public class ProjectionGrantCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val cached: AtomicReference<ProjectionGrantSnapshot?> = AtomicReference(null)

    /** Read the cached snapshot. May return a stale one — caller checks freshness. */
    public fun snapshot(): ProjectionGrantSnapshot? = cached.get()

    /** Read only if still within TTL; otherwise null. */
    public fun freshSnapshot(): ProjectionGrantSnapshot? {
        val s = cached.get() ?: return null
        return if (s.isFresh(clock())) s else null
    }

    /**
     * Update the cache to reflect a fresh grant.
     */
    public fun recordGrant(
        triggerOrigin: String,
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        val now = clock()
        cached.set(
            ProjectionGrantSnapshot(
                granted = true,
                grantedAtEpochMs = now,
                triggerOrigin = triggerOrigin,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                cachedAtEpochMs = now,
                ttlMs = ttlMs,
            ),
        )
        DaemonLogger.get().info(
            TAG,
            "grant_cache.record origin=$triggerOrigin rateHz=$sampleRateHz ch=$channelCount",
        )
    }

    /** Invalidate the cache (called on revoke). */
    public fun recordRevoke() {
        cached.set(null)
        DaemonLogger.get().info(TAG, "grant_cache.invalidate")
    }

    public companion object {
        /** 5 minutes — long enough for in-process state transitions, short enough to avoid stale-reads after a restart. */
        public const val DEFAULT_TTL_MS: Long = 5L * 60L * 1_000L
        private const val TAG: String = "ProjectionGrantCache"
    }
}

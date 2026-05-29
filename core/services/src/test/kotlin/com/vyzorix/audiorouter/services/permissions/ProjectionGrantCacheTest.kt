package com.vyzorix.audiorouter.services.permissions

import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionGrantCacheTest {

    @Test
    fun `snapshot returns null before any grant`() {
        val cache = ProjectionGrantCache()
        assertNull(cache.snapshot())
        assertNull(cache.freshSnapshot())
    }

    @Test
    fun `recordGrant exposes a fresh snapshot with the metadata`() {
        val cache = ProjectionGrantCache(clock = { 1_000L })
        cache.recordGrant(
            triggerOrigin = "bootstrap",
            sampleRateHz = 48_000,
            channelCount = 1,
        )
        val snap = cache.snapshot()
        assertNotNull(snap)
        assertTrue(snap.granted)
        assertEquals("bootstrap", snap.triggerOrigin)
        assertEquals(48_000, snap.sampleRateHz)
        assertEquals(1, snap.channelCount)
        assertEquals(1_000L, snap.grantedAtEpochMs)
        assertEquals(1_000L, snap.cachedAtEpochMs)
    }

    @Test
    fun `freshSnapshot returns null once TTL has elapsed`() {
        val now = AtomicLong(1_000L)
        val cache = ProjectionGrantCache(ttlMs = 5_000L, clock = { now.get() })
        cache.recordGrant(triggerOrigin = "bootstrap", sampleRateHz = 48_000, channelCount = 1)
        // Within TTL.
        now.set(5_500L)
        assertNotNull(cache.freshSnapshot())
        // After TTL.
        now.set(6_001L)
        assertNull(cache.freshSnapshot())
        // Stale read still returns the entry.
        assertNotNull(cache.snapshot())
    }

    @Test
    fun `recordRevoke invalidates the cache`() {
        val cache = ProjectionGrantCache(clock = { 1_000L })
        cache.recordGrant(triggerOrigin = "bootstrap", sampleRateHz = 48_000, channelCount = 1)
        assertNotNull(cache.snapshot())
        cache.recordRevoke()
        assertNull(cache.snapshot())
        assertNull(cache.freshSnapshot())
    }

    @Test
    fun `isFresh respects the configured TTL boundary`() {
        val snap = ProjectionGrantSnapshot(
            granted = true,
            grantedAtEpochMs = 1_000L,
            triggerOrigin = "bootstrap",
            sampleRateHz = 48_000,
            channelCount = 1,
            cachedAtEpochMs = 1_000L,
            ttlMs = 100L,
        )
        assertTrue(snap.isFresh(1_050L))
        assertFalse(snap.isFresh(1_100L))
        assertFalse(snap.isFresh(2_000L))
    }
}

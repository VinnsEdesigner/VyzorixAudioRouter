package com.vyzorix.audiorouter.services.capture

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturePermissionStoreTest {

    @Test
    fun `initial state is not granted`() = runTest {
        val store = fakeCapturePermissionStore()
        val state = store.currentState()
        assertFalse(state.granted)
        assertNull(state.grantedAtEpochMs)
        assertNull(state.revokedAtEpochMs)
        assertNull(state.triggerOrigin)
    }

    @Test
    fun `recordGrant flips granted state and writes through to the persisted store`() = runTest {
        val store = fakeCapturePermissionStore()
        store.recordGrant(
            grantedAtEpochMs = 100_000L,
            sampleRateHz = 48_000,
            channelCount = 1,
            triggerOrigin = "bootstrap",
        )
        val state = store.currentState()
        assertTrue(state.granted)
        assertEquals(100_000L, state.grantedAtEpochMs)
        assertNull(state.revokedAtEpochMs)
        assertEquals("bootstrap", state.triggerOrigin)

        val persisted = store.snapshotPersisted()
        assertEquals(100_000L, persisted.lastSessionStartEpochMs)
        assertEquals(48_000, persisted.lastSampleRateHz)
        assertEquals(1, persisted.lastChannelCount)
        assertEquals("bootstrap", persisted.lastTriggerOrigin)
    }

    @Test
    fun `recordRevoke flips granted false and stamps the revoke epoch`() = runTest {
        val store = fakeCapturePermissionStore()
        store.recordGrant(
            grantedAtEpochMs = 100_000L,
            sampleRateHz = 48_000,
            channelCount = 1,
            triggerOrigin = "bootstrap",
        )
        store.recordRevoke(revokedAtEpochMs = 200_000L)
        val state = store.currentState()
        assertFalse(state.granted)
        assertEquals(100_000L, state.grantedAtEpochMs)
        assertEquals(200_000L, state.revokedAtEpochMs)

        val persisted = store.snapshotPersisted()
        assertEquals(200_000L, persisted.lastSessionStopEpochMs)
    }

    @Test
    fun `lastPersistedSessionStartEpochMs flow updates after recordGrant`() = runTest {
        val store = fakeCapturePermissionStore()
        // Drive flow value through a write.
        store.recordGrant(
            grantedAtEpochMs = 42L,
            sampleRateHz = 16_000,
            channelCount = 1,
            triggerOrigin = "auto_reacquire",
        )
        val persisted = store.snapshotPersisted()
        assertNotNull(persisted.lastSessionStartEpochMs)
        assertEquals(42L, persisted.lastSessionStartEpochMs)
    }
}

package com.vyzorix.audiorouter.services.capture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionTokenManagerTest {

    @Test
    fun `initial snapshot is inactive`() {
        val scope = TestScope(StandardTestDispatcher())
        val manager = fakeProjectionTokenManager(scope = scope)
        val snap = manager.currentSnapshot()
        assertFalse(snap.isActive)
        assertNull(snap.resultCode)
        assertNull(snap.grantedAtEpochMs)
        assertNull(snap.triggerOrigin)
    }

    @Test
    fun `recordGrant updates snapshot synchronously and emits Granted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val now = AtomicLong(1_000L)
        val permissionStore = fakeCapturePermissionStore()
        val persistence = TokenPersistence(tokenEncryptor = fakeTokenEncryptor())
        val manager = ProjectionTokenManager(
            scope = scope,
            permissionStore = permissionStore,
            tokenPersistence = persistence,
            clock = { now.get() },
        )

        manager.recordGrant(
            resultCode = -1,
            triggerOrigin = "bootstrap",
            config = AudioCaptureConfig.DEFAULT,
        )
        val snap = manager.currentSnapshot()
        assertTrue(snap.isActive)
        assertEquals(-1, snap.resultCode)
        assertEquals(1_000L, snap.grantedAtEpochMs)
        assertEquals("bootstrap", snap.triggerOrigin)

        val first = manager.events.first()
        check(first is ProjectionTokenEvent.Granted)
        assertEquals(1_000L, first.grantedAtEpochMs)

        scope.advanceUntilIdle()
        // Persistence has been written through.
        val persisted = persistence.read()
        assertEquals(1_000L, persisted?.grantedAtEpochMs)
    }

    @Test
    fun `recordRevoke deactivates and emits Revoked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val now = AtomicLong(1_000L)
        val manager = ProjectionTokenManager(
            scope = scope,
            permissionStore = fakeCapturePermissionStore(),
            tokenPersistence = TokenPersistence(tokenEncryptor = fakeTokenEncryptor()),
            clock = { now.get() },
        )

        manager.recordGrant(
            resultCode = -1,
            triggerOrigin = "bootstrap",
            config = AudioCaptureConfig.DEFAULT,
        )
        scope.advanceUntilIdle()

        now.set(2_000L)
        manager.recordRevoke(reason = "user_stop")
        val snap = manager.currentSnapshot()
        assertFalse(snap.isActive)
        // Latest replayed event should be the Revoked.
        val latest = manager.events.first()
        check(latest is ProjectionTokenEvent.Revoked)
        assertEquals(2_000L, latest.revokedAtEpochMs)
        assertEquals("user_stop", latest.reason)
    }

    @Test
    fun `readPersistedConfig falls back to DEFAULT when nothing persisted`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = fakeProjectionTokenManager(scope = scope)
        val config = manager.readPersistedConfig()
        assertEquals(AudioCaptureConfig.DEFAULT, config)
    }
}

package com.vyzorix.audiorouter.services.capture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionDeathHandlerTest {

    private class RecordingListener : ProjectionDeathListener {
        var deathCount: Int = 0
        var recoveryGaveUp: Boolean = false
        override fun onProjectionDied() { deathCount += 1 }
        override fun onRecoveryGaveUp() { recoveryGaveUp = true }
    }

    @Test
    fun `onProjectionStopped without a bound listener still increments the counter`() {
        val handler = ProjectionDeathHandler(clock = { 0L })
        handler.onProjectionStopped()
        handler.onProjectionStopped()
        assertEquals(2, handler.totalProjectionDeaths)
    }

    @Test
    fun `recordRecoveryAttempt returns RELAUNCH below the threshold`() {
        val handler = ProjectionDeathHandler(
            clock = { 0L },
            failureThresholdCount = 3,
        )
        assertEquals(RecoveryDecision.RELAUNCH_TRAMPOLINE, handler.recordRecoveryAttempt(success = false))
        assertEquals(RecoveryDecision.RELAUNCH_TRAMPOLINE, handler.recordRecoveryAttempt(success = false))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `recordRecoveryAttempt returns FALLBACK once the threshold is hit inside the window`() {
        val now = AtomicLong(1_000L)
        val listener = RecordingListener()
        val handler = ProjectionDeathHandler(
            clock = { now.get() },
            failureThresholdCount = 3,
            failureWindowMs = 10_000L,
        )
        val scope = TestScope(StandardTestDispatcher())
        handler.bind(
            tokenManager = fakeProjectionTokenManager(scope = scope),
            idleController = null,
            listener = listener,
        )
        handler.recordRecoveryAttempt(success = false)
        handler.recordRecoveryAttempt(success = false)
        val decision = handler.recordRecoveryAttempt(success = false)
        assertEquals(RecoveryDecision.FALLBACK_VOIP_ONLY, decision)
        assertTrue(listener.recoveryGaveUp)
    }

    @Test
    fun `failures outside the window are discarded`() {
        val now = AtomicLong(0L)
        val handler = ProjectionDeathHandler(
            clock = { now.get() },
            failureThresholdCount = 3,
            failureWindowMs = 100L,
        )
        handler.recordRecoveryAttempt(success = false)
        now.set(200L)
        handler.recordRecoveryAttempt(success = false)
        now.set(300L)
        // First failure is now outside the 100ms window.
        val decision = handler.recordRecoveryAttempt(success = false)
        // We've effectively had 2 in-window failures, still below threshold (3).
        assertEquals(RecoveryDecision.RELAUNCH_TRAMPOLINE, decision)
    }
}

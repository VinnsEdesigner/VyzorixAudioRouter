package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.enums.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecoveryCoordinatorTest {

    private val restartCount = AtomicInteger(0)
    private val stopCount = AtomicInteger(0)
    private val safeModeStateRef = AtomicReference<Boolean>(null)

    private fun mkAggregator(): DaemonStatusAggregator {
        val ctx = object : DaemonStatusContextProvider {
            override fun daemonState(): DaemonState = DaemonState.RUNNING
            override fun routeState(): RouteState = RouteState.SPEAKER_FORCED
            override fun captureState(): CaptureState = CaptureState.ACTIVE
            override fun lastCommandAtEpochMs(): Long? = null
            override fun websocketConnected(): Boolean = false
        }
        return DaemonStatusAggregator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            contextProvider = ctx,
        )
    }

    private fun mkCallback() = object : RecoveryCallback {
        override fun restartPipeline(reason: String) { restartCount.incrementAndGet() }
        override fun stopForGood(reason: String) { stopCount.incrementAndGet() }
        override fun onSafeModeChanged(active: Boolean, reason: String) { safeModeStateRef.set(active) }
    }

    @Test fun `STABLE risk yields NoAction or ExitSafeMode`() {
        val coordinator = RecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            aggregator = mkAggregator(),
            callback = mkCallback(),
        )
        val decision = coordinator.decide(RiskLevel.STABLE)
        assertTrue(decision is RecoveryDecision.NoAction || decision is RecoveryDecision.ExitSafeMode)
    }

    @Test fun `CRITICAL streak below threshold yields NoAction`() {
        val coordinator = RecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            aggregator = mkAggregator(),
            callback = mkCallback(),
        )
        for (i in 1 until RecoveryCoordinator.CRIT_STREAK_BEFORE_RESTART) {
            val d = coordinator.decide(RiskLevel.CRITICAL)
            assertTrue(d is RecoveryDecision.NoAction, "iter=$i got=$d")
        }
        val finalDecision = coordinator.decide(RiskLevel.CRITICAL)
        assertTrue(finalDecision is RecoveryDecision.RestartPipeline)
    }

    @Test fun `manual restart honours cooldown`() {
        val now = AtomicLong(0L)
        val coordinator = RecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            aggregator = mkAggregator(),
            callback = mkCallback(),
            clock = { now.get() },
        )
        // Advance clock past the initial cooldown window so the first
        // restart actually fires (production clock is wall-clock time;
        // the sentinel lastRestartEpochMs=0 read trips at t=0 only
        // because the fake clock starts at 0).
        now.set(60_000L)
        coordinator.requestRestart("first")
        val initialCount = restartCount.get()
        assertTrue(initialCount >= 1, "first restart should fire")
        coordinator.requestRestart("second_too_soon")
        assertEquals(initialCount, restartCount.get(), "second restart should be throttled")
    }

    @Test fun `crash loop limit triggers StopForGood`() {
        val now = AtomicLong(60_000L)
        val coordinator = RecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            aggregator = mkAggregator(),
            callback = mkCallback(),
            cooldownMs = 0L,
            crashLoopLimit = 2,
            clock = { now.get() },
        )
        coordinator.requestRestart("r1")
        // After restart, currentBackoffMs grows; bump clock past it.
        now.set(now.get() + 60_000L)
        coordinator.requestRestart("r2")
        now.set(now.get() + 60_000L)
        // History now contains 2 restarts. Next CRIT streak should
        // produce StopForGood.
        for (i in 1 until RecoveryCoordinator.CRIT_STREAK_BEFORE_RESTART) {
            coordinator.decide(RiskLevel.CRITICAL)
        }
        val decision = coordinator.decide(RiskLevel.CRITICAL)
        assertTrue(decision is RecoveryDecision.StopForGood, "expected StopForGood, got $decision")
    }

    @Test fun `engageSafeMode flips SafeModeProbe`() {
        val coordinator = RecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            aggregator = mkAggregator(),
            callback = mkCallback(),
        )
        assertEquals(false, coordinator.isActive())
        coordinator.engageSafeMode("test")
        assertEquals(true, coordinator.isActive())
        assertEquals("test", coordinator.lastEngagedReason())
        assertEquals(true, safeModeStateRef.get())
        coordinator.disengageSafeMode("test_done")
        assertEquals(false, coordinator.isActive())
        assertEquals(false, safeModeStateRef.get())
    }

    @Test fun `noteHealthyUptime resets backoff`() {
        val now = AtomicLong(60_000L)
        val coordinator = RecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            aggregator = mkAggregator(),
            callback = mkCallback(),
            clock = { now.get() },
        )
        coordinator.requestRestart("trigger")
        val backoffAfter = coordinator.snapshot().currentBackoffMs
        assertNotEquals(0L, backoffAfter)
        coordinator.noteHealthyUptime()
        assertEquals(0L, coordinator.snapshot().currentBackoffMs)
    }
}

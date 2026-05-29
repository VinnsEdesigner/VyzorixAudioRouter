package com.vyzorix.audiorouter.services.projection

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectionRetryPolicyTest {

    private val now: AtomicLong = AtomicLong(0L)

    private fun mk(
        cooldownMs: Long = 2_000L,
        maxLaunchesPerWindow: Int = 5,
        rollingWindowMs: Long = 60_000L,
        banAfter: Int = 3,
    ): ProjectionRetryPolicy = ProjectionRetryPolicy(
        cooldownMs = cooldownMs,
        maxLaunchesPerWindow = maxLaunchesPerWindow,
        rollingWindowMs = rollingWindowMs,
        banAfterConsecutiveDenials = banAfter,
        clock = { now.get() },
    )

    @Test fun `first call yields Allow`() {
        val p = mk()
        assertTrue(p.tryAcquireLaunchSlot() is RetryDecision.Allow)
    }

    @Test fun `second call inside cooldown yields Throttle`() {
        val p = mk(cooldownMs = 2_000L)
        now.set(100L)
        p.tryAcquireLaunchSlot()
        now.set(500L)
        val d = p.tryAcquireLaunchSlot()
        assertTrue(d is RetryDecision.Throttle, "expected Throttle, got $d")
    }

    @Test fun `second call after cooldown yields Allow`() {
        val p = mk(cooldownMs = 2_000L)
        now.set(100L)
        p.tryAcquireLaunchSlot()
        now.set(2_500L)
        assertTrue(p.tryAcquireLaunchSlot() is RetryDecision.Allow)
    }

    @Test fun `consecutive denials trigger Banned`() {
        val p = mk(cooldownMs = 0L, banAfter = 3)
        p.tryAcquireLaunchSlot()
        p.recordDenial("user")
        now.set(10L)
        p.tryAcquireLaunchSlot()
        p.recordDenial("user")
        now.set(20L)
        p.tryAcquireLaunchSlot()
        p.recordDenial("user")
        now.set(30L)
        val d = p.tryAcquireLaunchSlot()
        assertTrue(d is RetryDecision.Banned, "expected Banned, got $d")
    }

    @Test fun `grant resets backoff and consecutive_denials`() {
        val p = mk(cooldownMs = 0L)
        p.tryAcquireLaunchSlot()
        p.recordDenial("user")
        now.set(100L)
        p.tryAcquireLaunchSlot()
        p.recordGrant()
        val s = p.snapshot()
        assertEquals(0, s.consecutiveDenials)
    }
}

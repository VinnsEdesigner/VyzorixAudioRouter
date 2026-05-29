package com.vyzorix.audiorouter.services.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnderrunRecoveryTest {

    @Test
    fun `advise returns PlayCaptured when capture has enough bytes`() {
        val recovery = UnderrunRecovery(silenceBufferBytes = 1024)
        val decision = recovery.advise(availableCapturedBytes = 4096, requestedBytes = 1024)
        assertSame(UnderrunDecision.PlayCaptured, decision)
        assertEquals(0L, recovery.totalSilenceInjections)
        assertEquals(0L, recovery.totalSilenceBytesInjected)
    }

    @Test
    fun `advise returns InjectSilence sized to the gap when capture starves`() {
        val recovery = UnderrunRecovery(silenceBufferBytes = 4096)
        val decision = recovery.advise(availableCapturedBytes = 100, requestedBytes = 1024)
        check(decision is UnderrunDecision.InjectSilence)
        assertEquals(924, decision.silenceLengthBytes)
        // The pre-allocated buffer is reused.
        assertEquals(4096, decision.silenceBytes.size)
        decision.silenceBytes.forEach { assertEquals(0.toByte(), it) }
        assertEquals(1L, recovery.totalSilenceInjections)
        assertEquals(924L, recovery.totalSilenceBytesInjected)
    }

    @Test
    fun `gap is capped at the pre-allocated buffer size`() {
        val recovery = UnderrunRecovery(silenceBufferBytes = 256)
        val decision = recovery.advise(availableCapturedBytes = 0, requestedBytes = 4096)
        check(decision is UnderrunDecision.InjectSilence)
        assertEquals(256, decision.silenceLengthBytes)
        assertEquals(256L, recovery.totalSilenceBytesInjected)
    }

    @Test
    fun `reset clears the running counters`() {
        val recovery = UnderrunRecovery(silenceBufferBytes = 256)
        recovery.advise(availableCapturedBytes = 0, requestedBytes = 1024)
        assertTrue(recovery.totalSilenceInjections > 0L)
        recovery.reset()
        assertEquals(0L, recovery.totalSilenceInjections)
        assertEquals(0L, recovery.totalSilenceBytesInjected)
    }

    @Test
    fun `LatencyOptimizer is notified on every underrun`() {
        val optimizer = LatencyOptimizer()
        val recovery = UnderrunRecovery(silenceBufferBytes = 256, latencyOptimizer = optimizer)
        repeat(5) {
            recovery.advise(availableCapturedBytes = 0, requestedBytes = 128)
        }
        val snapshot = optimizer.snapshot()
        assertTrue(snapshot.underrunCount >= 5L)
    }
}

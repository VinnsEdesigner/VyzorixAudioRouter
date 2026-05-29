package com.vyzorix.audiorouter.services.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatencyOptimizerTest {

    @Test
    fun `empty state yields HOLD with zero averages`() {
        val optimizer = LatencyOptimizer()
        val snap = optimizer.snapshot()
        assertEquals(0L, snap.avgWriteLatencyMicros)
        assertEquals(0L, snap.underrunCount)
        assertEquals(0L, snap.overrunCount)
        assertEquals(DesiredBufferState.HOLD, snap.suggestion)
    }

    @Test
    fun `recordWriteLatency averages over the rolling window`() {
        val optimizer = LatencyOptimizer()
        optimizer.recordWriteLatency(100L)
        optimizer.recordWriteLatency(200L)
        optimizer.recordWriteLatency(300L)
        val snap = optimizer.snapshot()
        assertEquals(200L, snap.avgWriteLatencyMicros)
        assertEquals(3L, snap.sampleCount)
    }

    @Test
    fun `GROW is suggested only when both underruns and write latency cross the threshold`() {
        val optimizer = LatencyOptimizer(
            underrunGrowThreshold = 2,
            targetWriteLatencyMicros = 10_000L,
        )
        // Underruns alone: still HOLD.
        repeat(5) { optimizer.recordUnderrun() }
        assertEquals(DesiredBufferState.HOLD, optimizer.snapshot().suggestion)

        // Add high-latency writes.
        repeat(3) { optimizer.recordWriteLatency(15_000L) }
        assertEquals(DesiredBufferState.GROW, optimizer.snapshot().suggestion)
    }

    @Test
    fun `SHRINK is suggested when overruns dominate and latency is well under target`() {
        val optimizer = LatencyOptimizer(
            overrunShrinkThreshold = 4,
            targetWriteLatencyMicros = 10_000L,
        )
        repeat(5) { optimizer.recordOverrun() }
        repeat(5) { optimizer.recordWriteLatency(1_000L) }
        assertEquals(DesiredBufferState.SHRINK, optimizer.snapshot().suggestion)
    }

    @Test
    fun `nextBufferSize grows by 1_5x and clamps to MAX_BUFFER_BYTES`() {
        val optimizer = LatencyOptimizer()
        assertEquals(6_000, optimizer.nextBufferSize(4_000, DesiredBufferState.GROW))
        assertEquals(
            LatencyOptimizer.MAX_BUFFER_BYTES,
            optimizer.nextBufferSize(LatencyOptimizer.MAX_BUFFER_BYTES, DesiredBufferState.GROW),
        )
    }

    @Test
    fun `nextBufferSize shrinks by 0_75x and clamps to MIN_BUFFER_BYTES`() {
        val optimizer = LatencyOptimizer()
        assertEquals(3_000, optimizer.nextBufferSize(4_000, DesiredBufferState.SHRINK))
        assertEquals(
            LatencyOptimizer.MIN_BUFFER_BYTES,
            optimizer.nextBufferSize(LatencyOptimizer.MIN_BUFFER_BYTES, DesiredBufferState.SHRINK),
        )
    }

    @Test
    fun `nextBufferSize is identity on HOLD`() {
        val optimizer = LatencyOptimizer()
        assertEquals(4_000, optimizer.nextBufferSize(4_000, DesiredBufferState.HOLD))
    }

    @Test
    fun `reset zeroes all rolling counters`() {
        val optimizer = LatencyOptimizer()
        repeat(3) {
            optimizer.recordUnderrun()
            optimizer.recordOverrun()
            optimizer.recordWriteLatency(5_000L)
        }
        assertTrue(optimizer.snapshot().sampleCount > 0L)
        optimizer.reset()
        val cleared = optimizer.snapshot()
        assertEquals(0L, cleared.sampleCount)
        assertEquals(0L, cleared.underrunCount)
        assertEquals(0L, cleared.overrunCount)
        assertEquals(0L, cleared.avgWriteLatencyMicros)
    }
}

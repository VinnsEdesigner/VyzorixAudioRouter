// LatencyOptimizer — dynamic buffer-size sampling for the playback side.
//
// The optimizer observes:
//   - Underrun events (silence frames injected via UnderrunRecovery).
//   - Overrun events (capture writes outpacing playback).
//   - Rolling average of write latency.
//
// Based on the rolling stats, it suggests a [DesiredBufferState] —
// either a larger buffer (if underruns dominate) or a smaller buffer
// (if we have headroom).
//
// Adjustments are SUGGESTIONS, not commands. The actual buffer resize
// requires tearing down + rebuilding the AudioTrack, which the caller
// (SpeakerPlaybackEngine) decides whether to honour.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §5.4.

package com.vyzorix.audiorouter.services.playback

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Latency snapshot returned by [LatencyOptimizer.snapshot]. */
public data class LatencySnapshot(
    public val avgWriteLatencyMicros: Long,
    public val underrunCount: Long,
    public val overrunCount: Long,
    public val sampleCount: Long,
    public val suggestion: DesiredBufferState,
)

/** Recommended action for the playback engine. */
public enum class DesiredBufferState {
    /** Underrun pressure — request larger AudioTrack buffer. */
    GROW,
    /** Overrun pressure — request smaller buffer. */
    SHRINK,
    /** Within thresholds. */
    HOLD,
}

/**
 * Rolling-window latency optimizer. Thread-safe via atomic counters; no
 * coroutine dispatch.
 */
public class LatencyOptimizer(
    private val underrunGrowThreshold: Int = DEFAULT_UNDERRUN_GROW_THRESHOLD,
    private val overrunShrinkThreshold: Int = DEFAULT_OVERRUN_SHRINK_THRESHOLD,
    private val targetWriteLatencyMicros: Long = DEFAULT_TARGET_WRITE_LATENCY_MICROS,
) {

    private val totalWriteLatencyMicros: AtomicLong = AtomicLong(0L)
    private val sampleCount: AtomicLong = AtomicLong(0L)
    private val underrunCount: AtomicLong = AtomicLong(0L)
    private val overrunCount: AtomicLong = AtomicLong(0L)

    /** Record a single AudioTrack.write() duration (in microseconds). */
    public fun recordWriteLatency(latencyMicros: Long) {
        totalWriteLatencyMicros.addAndGet(max(0L, latencyMicros))
        sampleCount.incrementAndGet()
    }

    /** Increment the underrun counter. */
    public fun recordUnderrun() {
        underrunCount.incrementAndGet()
    }

    /** Increment the overrun counter. */
    public fun recordOverrun() {
        overrunCount.incrementAndGet()
    }

    /**
     * Suggest a buffer adjustment given the current rolling stats. Caller
     * is free to ignore.
     */
    public fun snapshot(): LatencySnapshot {
        val samples = sampleCount.get()
        val avg = if (samples > 0) totalWriteLatencyMicros.get() / samples else 0L
        val underruns = underrunCount.get()
        val overruns = overrunCount.get()
        val suggestion = when {
            underruns >= underrunGrowThreshold && avg >= targetWriteLatencyMicros -> DesiredBufferState.GROW
            overruns >= overrunShrinkThreshold && avg < targetWriteLatencyMicros / 2 -> DesiredBufferState.SHRINK
            else -> DesiredBufferState.HOLD
        }
        if (suggestion != DesiredBufferState.HOLD) {
            DaemonLogger.get().info(
                TAG,
                "latency.suggest action=$suggestion avgMicros=$avg underruns=$underruns overruns=$overruns",
            )
        }
        return LatencySnapshot(
            avgWriteLatencyMicros = avg,
            underrunCount = underruns,
            overrunCount = overruns,
            sampleCount = samples,
            suggestion = suggestion,
        )
    }

    /** Reset all rolling counters. */
    public fun reset() {
        totalWriteLatencyMicros.set(0L)
        sampleCount.set(0L)
        underrunCount.set(0L)
        overrunCount.set(0L)
    }

    /** Compute the next buffer size given a previous size + suggestion. */
    public fun nextBufferSize(currentBytes: Int, suggestion: DesiredBufferState): Int {
        val grown = (currentBytes * GROW_FACTOR_NUM) / GROW_FACTOR_DEN
        val shrunk = (currentBytes * SHRINK_FACTOR_NUM) / SHRINK_FACTOR_DEN
        return when (suggestion) {
            DesiredBufferState.GROW -> min(grown, MAX_BUFFER_BYTES)
            DesiredBufferState.SHRINK -> max(shrunk, MIN_BUFFER_BYTES)
            DesiredBufferState.HOLD -> currentBytes
        }
    }

    public companion object {
        public const val DEFAULT_UNDERRUN_GROW_THRESHOLD: Int = 3
        public const val DEFAULT_OVERRUN_SHRINK_THRESHOLD: Int = 16
        public const val DEFAULT_TARGET_WRITE_LATENCY_MICROS: Long = 10_000L

        /** Min/max safety floors for [nextBufferSize]. */
        public const val MIN_BUFFER_BYTES: Int = 1024
        public const val MAX_BUFFER_BYTES: Int = 64 * 1024

        // 1.5x growth, 0.75x shrink.
        private const val GROW_FACTOR_NUM: Int = 3
        private const val GROW_FACTOR_DEN: Int = 2
        private const val SHRINK_FACTOR_NUM: Int = 3
        private const val SHRINK_FACTOR_DEN: Int = 4

        private const val TAG: String = "LatencyOptimizer"
    }
}

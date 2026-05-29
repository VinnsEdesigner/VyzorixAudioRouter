// UnderrunRecovery — silence-frame injection when the capture side starves
// the playback side.
//
// The native `underrun_guard.cpp` handles the in-engine flag tracking;
// this class is the Kotlin-side policy:
//   - Decide WHEN to inject silence (capture ring buffer drained AND
//     playback wants more data).
//   - Generate the silence bytes (zero-padded S16LE).
//   - Bookkeep the injected-frame count for LatencyOptimizer.
//
// Threading: this is called from the playback loop on a real-time thread.
// Methods MUST NOT allocate (so the silence buffer is pre-allocated on
// construction and reused).
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §5.9.

package com.vyzorix.audiorouter.services.playback

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong

/** Recovery decision returned by [UnderrunRecovery.advise]. */
public sealed interface UnderrunDecision {
    /** Write the buffer at [silenceBytes] (read-only) for [silenceLengthBytes]. */
    public data class InjectSilence(
        public val silenceBytes: ByteArray,
        public val silenceLengthBytes: Int,
    ) : UnderrunDecision {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InjectSilence) return false
            return silenceLengthBytes == other.silenceLengthBytes
        }
        override fun hashCode(): Int = silenceLengthBytes.hashCode()
    }
    /** Caller should write the supplied PCM buffer instead. */
    public object PlayCaptured : UnderrunDecision
}

/**
 * Underrun recovery policy. One instance per playback engine; pre-allocates
 * the silence buffer at construction so the hot path is allocation-free.
 */
public class UnderrunRecovery(
    private val silenceBufferBytes: Int = DEFAULT_SILENCE_BUFFER_BYTES,
    private val latencyOptimizer: LatencyOptimizer? = null,
) {

    private val silenceBuffer: ByteArray = ByteArray(silenceBufferBytes)
    private val totalInjected: AtomicLong = AtomicLong(0L)
    private val totalBytesInjected: AtomicLong = AtomicLong(0L)

    /** Total recovery injections since process start. */
    public val totalSilenceInjections: Long get() = totalInjected.get()

    /** Total bytes of silence injected. */
    public val totalSilenceBytesInjected: Long get() = totalBytesInjected.get()

    /**
     * Decide what to write to AudioTrack given the available captured
     * bytes. If [availableCapturedBytes] is zero or below the request
     * size, silence is injected for the gap.
     */
    public fun advise(
        availableCapturedBytes: Int,
        requestedBytes: Int,
    ): UnderrunDecision {
        if (availableCapturedBytes >= requestedBytes) {
            return UnderrunDecision.PlayCaptured
        }
        val gap = requestedBytes - availableCapturedBytes
        val toInject = minOf(gap, silenceBufferBytes)
        totalInjected.incrementAndGet()
        totalBytesInjected.addAndGet(toInject.toLong())
        latencyOptimizer?.recordUnderrun()
        if (totalInjected.get() % LOG_EVERY_N == 0L) {
            DaemonLogger.get().warn(
                TAG,
                "underrun.injected count=${totalInjected.get()} bytesTotal=${totalBytesInjected.get()} thisFrameBytes=$toInject",
            )
        }
        return UnderrunDecision.InjectSilence(
            silenceBytes = silenceBuffer,
            silenceLengthBytes = toInject,
        )
    }

    /** Reset all bookkeeping. */
    public fun reset() {
        totalInjected.set(0L)
        totalBytesInjected.set(0L)
    }

    public companion object {
        /** 2 KiB silence buffer — enough for ~10 ms at 48 kHz mono S16LE. */
        public const val DEFAULT_SILENCE_BUFFER_BYTES: Int = 2 * 1024
        private const val LOG_EVERY_N: Long = 50L
        private const val TAG: String = "UnderrunRecovery"
    }
}

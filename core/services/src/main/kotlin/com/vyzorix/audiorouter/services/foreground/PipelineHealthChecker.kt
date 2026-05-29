// PipelineHealthChecker — Layer-B signal source that answers
// "is audio flowing?"
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md lines 617–622:
//     core/services/foreground/PipelineHealthChecker.kt
//       "Layer B signal: answers 'is audio flowing?' Monitors AudioRecord
//        read loop and AudioTrack write loop. Absorbs the responsibilities
//        of the former RendererFailureDetector (surfaceflinger stalls
//        show up here). Reports state to DaemonStatusAggregator;
//        distinct from LivenessProbe (broader daemon health)."
//
// Inputs (pushed by the existing daemon classes):
//   - PlaybackCaptureEngine — each successful read into the ring buffer
//     calls [recordCaptureFrame] with the frame size and timestamp.
//   - SpeakerPlaybackEngine — each successful AudioTrack.write call
//     calls [recordPlaybackFrame] with the frame size and timestamp.
//
// Banding:
//   - Both surfaces wrote a frame within the last 2s             → OK
//   - One surface wrote within 2s, the other did not             → WARN
//   - Neither surface wrote within 5s                            → CRIT
//   - Capture is intentionally idle (idleController paused)      → OK
//     (the checker is told via [setIdle])
//
// The checker is a [SignalSource] so it plugs into the same aggregator
// machinery as the other Layer-B signals.

package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import com.vyzorix.audiorouter.services.foreground.signals.SignalSource
import com.vyzorix.audiorouter.services.foreground.signals.SignalValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Pipeline-health signal source. Single-instance per service. */
public class PipelineHealthChecker(
    private val warnStalenessMs: Long = DEFAULT_WARN_STALENESS_MS,
    private val critStalenessMs: Long = DEFAULT_CRIT_STALENESS_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    public override val id: String = "pipeline_health"

    private val lastCaptureFrameEpochMs: AtomicLong = AtomicLong(0L)
    private val lastPlaybackFrameEpochMs: AtomicLong = AtomicLong(0L)
    private val captureFrameCount: AtomicLong = AtomicLong(0L)
    private val playbackFrameCount: AtomicLong = AtomicLong(0L)
    private val capturedBytes: AtomicLong = AtomicLong(0L)
    private val playedBytes: AtomicLong = AtomicLong(0L)
    private val idle: AtomicBoolean = AtomicBoolean(false)

    /** Called by `PlaybackCaptureEngine` on every successful frame. */
    public fun recordCaptureFrame(byteCount: Int, atEpochMs: Long = clock()) {
        captureFrameCount.incrementAndGet()
        capturedBytes.addAndGet(byteCount.toLong())
        lastCaptureFrameEpochMs.set(atEpochMs)
    }

    /** Called by `SpeakerPlaybackEngine` on every successful write. */
    public fun recordPlaybackFrame(byteCount: Int, atEpochMs: Long = clock()) {
        playbackFrameCount.incrementAndGet()
        playedBytes.addAndGet(byteCount.toLong())
        lastPlaybackFrameEpochMs.set(atEpochMs)
    }

    /**
     * Set the idle flag. While idle the checker returns OK even if no
     * frames have been recorded — this is the intentional state when
     * [IdleCaptureController] has paused capture.
     */
    public fun setIdle(value: Boolean) {
        idle.set(value)
    }

    public override fun current(): SignalValue {
        val now = clock()
        if (idle.get()) {
            return SignalValue(
                severity = SignalSeverity.OK,
                label = "pipeline idle",
                details = "captureFrames=${captureFrameCount.get()} playbackFrames=${playbackFrameCount.get()}",
                readEpochMs = now,
            )
        }
        val captureLast = lastCaptureFrameEpochMs.get()
        val playbackLast = lastPlaybackFrameEpochMs.get()
        val captureStale = if (captureLast == 0L) Long.MAX_VALUE else now - captureLast
        val playbackStale = if (playbackLast == 0L) Long.MAX_VALUE else now - playbackLast
        val severity = when {
            captureStale <= warnStalenessMs && playbackStale <= warnStalenessMs -> SignalSeverity.OK
            captureStale > critStalenessMs && playbackStale > critStalenessMs -> SignalSeverity.CRIT
            else -> SignalSeverity.WARN
        }
        val label = "capture ${captureStale.coerceAtMost(99_999)}ms / playback " +
            "${playbackStale.coerceAtMost(99_999)}ms stale"
        return SignalValue(
            severity = severity,
            label = label,
            details = "captureFrames=${captureFrameCount.get()} playbackFrames=${playbackFrameCount.get()} " +
                "capturedBytes=${capturedBytes.get()} playedBytes=${playedBytes.get()}",
            readEpochMs = now,
        )
    }

    public companion object {
        public const val DEFAULT_WARN_STALENESS_MS: Long = 2_000L
        public const val DEFAULT_CRIT_STALENESS_MS: Long = 5_000L
    }
}

// IdleCaptureController — silence-detection-driven pause/resume for the
// PCM capture path.
//
// Per doc/MEDIA_PROJECTION_FLOW.md §Mitigation 1:
//   - Threshold: 30s of consecutive silence triggers pause.
//   - "Pause" semantics: stop native ring buffer reads. The AudioTrack
//     stays open in MODE_IN_COMMUNICATION so the VoIP routing exemption
//     persists. ~60% CPU drop expected (verified ad hoc on the Nokia C22).
//   - Resume: immediate on any non-silent frame, with a target latency
//     of <200 ms.
//
// "Silence" definition: the maximum 16-bit sample value in a frame is
// below a configurable threshold (default −60 dBFS RMS, matching
// `AudioConstants.COMFORT_NOISE_DB_FS`).
//
// This class is INTENTIONALLY decoupled from `PlaybackCaptureEngine` — the
// engine focuses on "move PCM bytes", the controller wraps it with the
// silence policy. The controller drives the engine via `pause()`/`resume()`
// callbacks.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6 (extension) +
// doc/MEDIA_PROJECTION_FLOW.md §Mitigation 1.

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Hook fired when the controller changes state. */
public interface IdleCaptureListener {
    /** PCM reads should pause. */
    public fun onPause(reason: String)

    /** PCM reads should resume. */
    public fun onResume(reason: String)
}

/**
 * Idle-pause controller. Stateless w.r.t. the capture engine — callers
 * feed [observeFrame] for each PCM frame; the controller decides when to
 * pause/resume and fires the corresponding listener method.
 */
public class IdleCaptureController(
    listener: IdleCaptureListener? = null,
    private val silenceThresholdRms: Int = DEFAULT_SILENCE_THRESHOLD_RMS,
    private val silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val listenerRef: java.util.concurrent.atomic.AtomicReference<IdleCaptureListener?> =
        java.util.concurrent.atomic.AtomicReference(listener)
    private val listener: IdleCaptureListener
        get() = listenerRef.get() ?: NoopListener

    private val paused: AtomicBoolean = AtomicBoolean(false)
    private val silenceStartedAt: AtomicLong = AtomicLong(0L)

    /** Late-bind the listener (used to break the construction-time cycle). */
    public fun bind(listener: IdleCaptureListener) {
        listenerRef.set(listener)
    }

    private object NoopListener : IdleCaptureListener {
        override fun onPause(reason: String) {}
        override fun onResume(reason: String) {}
    }

    /** True iff we are currently in the paused state. */
    public val isPaused: Boolean get() = paused.get()

    /**
     * Feed a freshly-read PCM frame. The controller examines the
     * sample peak; if it is below [silenceThresholdRms], the silence
     * timer continues. If the timer elapses, [IdleCaptureListener.onPause]
     * is fired exactly once until activity resumes.
     *
     * Returns the current paused-state for the caller's convenience.
     */
    public fun observeFrame(pcm: ByteArray, offsetBytes: Int = 0, lengthBytes: Int = pcm.size): Boolean {
        val peak = computePeakAbs(pcm, offsetBytes, lengthBytes)
        val now = clock()
        if (peak < silenceThresholdRms) {
            // Silence observed.
            val start = silenceStartedAt.get()
            if (start == 0L) {
                silenceStartedAt.set(now)
            } else if (!paused.get() && (now - start) >= silenceDurationMs) {
                if (paused.compareAndSet(false, true)) {
                    DaemonLogger.get().info(
                        TAG,
                        "idle.pause reason=silence durationMs=${now - start} peak=$peak",
                    )
                    try {
                        listener.onPause(reason = "silence")
                    } catch (t: Throwable) {
                        DaemonLogger.get().warn(
                            TAG,
                            "idle.pause.listener_threw err=${t.javaClass.simpleName}",
                        )
                    }
                }
            }
        } else {
            // Activity observed — reset the silence timer + resume if needed.
            silenceStartedAt.set(0L)
            if (paused.get() && paused.compareAndSet(true, false)) {
                DaemonLogger.get().info(
                    TAG,
                    "idle.resume reason=activity peak=$peak",
                )
                try {
                    listener.onResume(reason = "activity")
                } catch (t: Throwable) {
                    DaemonLogger.get().warn(
                        TAG,
                        "idle.resume.listener_threw err=${t.javaClass.simpleName}",
                    )
                }
            }
        }
        return paused.get()
    }

    /**
     * Force-pause regardless of activity. Used by [ProjectionDeathHandler]
     * when the projection dies (silence-detection on a dead projection is
     * meaningless).
     */
    public fun pause(reason: String) {
        if (paused.compareAndSet(false, true)) {
            DaemonLogger.get().info(TAG, "idle.pause.forced reason=$reason")
            try {
                listener.onPause(reason = reason)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "idle.pause.listener_threw err=${t.javaClass.simpleName}",
                )
            }
        }
    }

    /** Force-resume regardless of activity. */
    public fun resume(reason: String) {
        silenceStartedAt.set(0L)
        if (paused.compareAndSet(true, false)) {
            DaemonLogger.get().info(TAG, "idle.resume.forced reason=$reason")
            try {
                listener.onResume(reason = reason)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "idle.resume.listener_threw err=${t.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * Compute the maximum absolute sample value in a S16LE PCM frame.
     * Returns 0 for a zero-length frame.
     */
    private fun computePeakAbs(pcm: ByteArray, offset: Int, length: Int): Int {
        if (length < 2) return 0
        require(offset >= 0 && length > 0 && offset + length <= pcm.size) {
            "frame bounds out of range: offset=$offset length=$length array.size=${pcm.size}"
        }
        var peak = 0
        var i = offset
        val end = offset + length - 1 // ensure pair available
        while (i < end) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() // signed
            val sample = (hi shl 8) or lo
            val absSample = abs(sample.toShort().toInt())
            if (absSample > peak) peak = absSample
            i += 2
        }
        return peak
    }

    public companion object {
        /**
         * Default silence threshold (S16LE absolute) — ~-60 dBFS. A 16-bit
         * sample max is 32767; 32767 × 10^(-60/20) ≈ 33. We use 64 as a
         * conservative threshold so faint background hum doesn't keep the
         * pipeline awake.
         */
        public const val DEFAULT_SILENCE_THRESHOLD_RMS: Int = 64
        /** 30 seconds — per MEDIA_PROJECTION_FLOW.md §Mitigation 1. */
        public const val DEFAULT_SILENCE_DURATION_MS: Long = 30_000L
        private const val TAG: String = "IdleCaptureController"
    }
}

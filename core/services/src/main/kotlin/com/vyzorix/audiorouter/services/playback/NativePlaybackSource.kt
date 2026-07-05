// NativePlaybackSource — pulls PCM from the native C++ ring buffer and
// forwards it to [SpeakerPlaybackEngine].
//
// Implements [PlaybackSource] so [SpeakerPlaybackEngine] can read from the
// native pipeline instead of directly from its internal queue. The source:
//   1. Is polled by [SpeakerPlaybackEngine] when it needs PCM bytes to write.
//   2. Reads from the native ring buffer via
//      [AudioPipelineController.pullPlaybackFrame] (JNI → libaudioengine.so).
//   3. Returns the number of bytes actually read; short reads indicate
//      underrun (the native layer injected comfort noise).
//
// The native ring buffer is lock-free SPSC (single-producer, single-consumer).
// This source is the *single consumer* — it must only be called from the
// playback thread. The producer is the capture path writing via
// [NativeFrameSink].
//
// Threading contract:
//   - All methods are safe to call from any single thread (the playback thread).
//   - Do NOT call from multiple threads concurrently.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.2
// and doc/MEDIA_PROJECTION_FLOW.md §Native Pipeline Integration.

package com.vyzorix.audiorouter.services.playback

import com.vyzorix.audiorouter.audioengine.AudioPipelineController
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/**
 * [PlaybackSource] implementation that reads PCM from the native C++ ring
 * buffer via JNI.
 *
 * This source replaces [SpeakerPlaybackEngine]'s internal [ArrayBlockingQueue]
 * with direct reads from the native pipeline, enabling:
 *   - Native DSP processing (gain, mixing)
 *   - Clock drift correction before output
 *   - Coordinated underrun handling with the native comfort-noise injector
 *
 * @param pipelineController the wired [AudioPipelineController] owning the
 *        native ring buffer handle. Must be started before this source is used.
 * @param underrunRecovery callback when short reads indicate underrun; the
 *        native layer has injected comfort noise but the engine may want to
 *        log or adjust its latency budget.
 */
public class NativePlaybackSource(
    private val pipelineController: AudioPipelineController,
    private val underrunRecovery: () -> Unit = {},
) : PlaybackSource {

    private var totalRead: Long = 0L
    private var totalUnderruns: Long = 0L
    private var consecutiveUnderruns: Int = 0

    /**
     * Reads PCM bytes from the native ring buffer.
     *
     * @param dst destination buffer to fill.
     * @param offsetBytes start offset in [dst]; always 0 in the canonical path.
     * @param lengthBytes maximum bytes to read; a multiple of the sample size
     *        (2 bytes for S16LE).
     * @return the number of bytes actually read; may be less than [lengthBytes]
     *         if the ring buffer is near-empty. Short reads trigger underrun
     *         handling.
     */
    public override fun read(dst: ByteArray, offsetBytes: Int, lengthBytes: Int): Int {
        val handle = pipelineController.ringBufferHandle
        if (handle == 0L) {
            if (consecutiveUnderruns == 0) {
                DaemonLogger.get().warn(TAG, "native_source.no_handle length=$lengthBytes")
            }
            consecutiveUnderruns++
            totalUnderruns++
            return 0
        }

        val readBytes = pipelineController.pullPlaybackFrame(
            dst = dst,
            offsetBytes = offsetBytes,
            lengthBytes = lengthBytes,
        )

        if (readBytes < lengthBytes) {
            consecutiveUnderruns++
            totalUnderruns++
            if (consecutiveUnderruns == 1) {
                DaemonLogger.get().debug(
                    TAG,
                    "native_source.underrun " +
                        "requested=$lengthBytes actual=$readBytes total_underruns=$totalUnderruns",
                )
            }
            underrunRecovery()
        } else {
            consecutiveUnderruns = 0
            totalRead++
        }

        return readBytes
    }

    /** Number of bytes currently available to read from the ring buffer. */
    public fun availableToRead(): Int {
        val handle = pipelineController.ringBufferHandle
        if (handle == 0L) return 0
        return pipelineController.availableReadBytes()
    }

    /** Snapshot of source telemetry for diagnostics. */
    public fun snapshot(): NativePlaybackSourceState = NativePlaybackSourceState(
        totalRead = totalRead,
        totalUnderruns = totalUnderruns,
        consecutiveUnderruns = consecutiveUnderruns,
        isPipelineActive = pipelineController.ringBufferHandle != 0L,
        bufferAvailableBytes = availableToRead(),
    )

    private companion object {
        private const val TAG: String = "NativePlaybackSource"
    }
}

/** Telemetry snapshot for [NativePlaybackSource]. */
public data class NativePlaybackSourceState(
    public val totalRead: Long,
    public val totalUnderruns: Long,
    public val consecutiveUnderruns: Int,
    public val isPipelineActive: Boolean,
    public val bufferAvailableBytes: Int,
)

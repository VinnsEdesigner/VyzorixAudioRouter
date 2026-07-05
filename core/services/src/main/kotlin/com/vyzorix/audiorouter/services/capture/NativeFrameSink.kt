// NativeFrameSink — bridges the Kotlin capture side to the native C++ pipeline.
//
// Implements [FrameSink] so [PlaybackCaptureEngine] can forward captured PCM
// through this sink instead of directly to [SpeakerPlaybackEngine]. The sink:
//   1. Receives PCM bytes via [onFrameCaptured].
//   2. Feeds them into the native ring buffer via
//      [AudioPipelineController.feedCapturedFrame] (JNI → libaudioengine.so).
//   3. Returns the number of bytes actually written; short writes indicate
//      the ring buffer is full (overrun counter is bumped in the native layer).
//
// The native ring buffer is lock-free SPSC (single-producer, single-consumer).
// This sink is the *single producer* — it must only be called from the
// capture thread. The consumer is the native playback path reading from the
// same ring buffer.
//
// Threading contract:
//   - All methods are safe to call from any single thread (the capture thread).
//   - The JNI bridge is thread-safe for single-producer use.
//   - Do NOT call from multiple threads concurrently.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.2
// and doc/MEDIA_PROJECTION_FLOW.md §Native Pipeline Integration.

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.audioengine.AudioPipelineController
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [FrameSink] implementation that forwards PCM frames into the native C++
 * ring buffer via JNI.
 *
 * This sink replaces the direct Java path (frame → AudioTrack) with a
 * JNI crossing that allows the native DSP layer to process the audio:
 *   - Real-time resampling (44.1kHz → 48kHz)
 *   - Clock drift correction
 *   - PCM mixing / gain control
 *   - Comfort noise injection on underrun
 *
 * The consumer side reads from the same ring buffer via
 * [AudioPipelineController.pullPlaybackFrame] in [SpeakerPlaybackEngine].
 *
 * @param pipelineController the wired [AudioPipelineController] owning the
 *        native ring buffer handle. Must be started before this sink is used.
 * @param maxDroppedPerWindow maximum consecutive dropped frames before a
 *        warning is emitted. Zero means never warn.
 */
public class NativeFrameSink(
    private val pipelineController: AudioPipelineController,
    private val maxDroppedPerWindow: Int = 10,
) : FrameSink {

    private var consecutiveDropped: Int = 0
    private var totalDropped: Long = 0L
    private var totalWritten: Long = 0L

    /**
     * Feeds captured PCM into the native ring buffer.
     *
     * @param pcm raw PCM bytes from [PlaybackCaptureEngine.AudioRecord.read].
     * @param offsetBytes start offset in [pcm]; always 0 in the canonical path.
     * @param lengthBytes number of bytes to feed; always a multiple of the
     *        sample size (2 bytes for S16LE).
     * @param captureEpochMs wall-clock epoch of the capture, used for
     *        diagnostic correlation.
     */
    public override fun onFrameCaptured(
        pcm: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
        captureEpochMs: Long,
    ) {
        val handle = pipelineController.ringBufferHandle
        if (handle == 0L) {
            if (consecutiveDropped == 0) {
                DaemonLogger.get().warn(TAG, "native_sink.no_handle dropping=$lengthBytes")
            }
            consecutiveDropped++
            totalDropped++
            return
        }

        val writtenBytes = pipelineController.feedCapturedFrame(
            pcm = pcm,
            offsetBytes = offsetBytes,
            lengthBytes = lengthBytes,
        )

        if (writtenBytes < lengthBytes) {
            // Ring buffer is full — the native layer dropped [lengthBytes - writtenBytes].
            consecutiveDropped++
            totalDropped++
            if (consecutiveDropped >= maxDroppedPerWindow && maxDroppedPerWindow > 0) {
                DaemonLogger.get().warn(
                    TAG,
                    "native_sink.overrun " +
                        "dropped=$consecutiveDropped total_dropped=$totalDropped " +
                        "capture_epoch_ms=$captureEpochMs",
                )
                consecutiveDropped = 0
            }
        } else {
            consecutiveDropped = 0
            totalWritten++
        }
    }

    /** Snapshot of sink telemetry for the dashboard. */
    public fun snapshot(): NativeFrameSinkState = NativeFrameSinkState(
        totalWritten = totalWritten,
        totalDropped = totalDropped,
        consecutiveDropped = consecutiveDropped,
        isPipelineActive = pipelineController.ringBufferHandle != 0L,
        bufferAvailableBytes = pipelineController.availableReadBytes(),
    )

    /** Resets consecutive-drop counter; called after a successful write window. */
    public fun resetConsecutiveDropped() {
        consecutiveDropped = 0
    }

    private companion object {
        private const val TAG: String = "NativeFrameSink"
    }
}

/** Telemetry snapshot for [NativeFrameSink]. */
public data class NativeFrameSinkState(
    public val totalWritten: Long,
    public val totalDropped: Long,
    public val consecutiveDropped: Int,
    public val isPipelineActive: Boolean,
    public val bufferAvailableBytes: Int,
)

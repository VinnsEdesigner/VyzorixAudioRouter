// PlaybackCaptureEngine — the actual `AudioRecord` capture loop.
//
// Responsibilities:
//   - Open an AudioRecord against the live MediaProjection (via
//     [PlaybackCaptureFactory]).
//   - Run a coroutine that calls AudioRecord.read() in a loop, into a
//     buffer acquired from [AudioBufferPool].
//   - Hand each captured frame to:
//       (a) IdleCaptureController.observeFrame(...) for silence detection.
//       (b) [FrameSink] (consumer wiring) which forwards the frame to the
//           native ring buffer via the audioengine bridge.
//   - Respond to pause/resume signals: while paused, read calls are
//     skipped but AudioRecord stays open (so resuming is instant).
//   - Detect underflow (short reads) and surface counters; the actual
//     recovery happens in CaptureRecoveryEngine.
//
// What it does NOT do:
//   - Decide WHEN to pause (that's IdleCaptureController).
//   - Decide WHEN to restart (that's CaptureRecoveryEngine).
//   - Manage the MediaProjection itself (that's MediaProjectionSession).
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.2 +
// doc/MEDIA_PROJECTION_FLOW.md §2 (Audio Pipeline).

package com.vyzorix.audiorouter.services.capture

import android.media.AudioRecord
import com.vyzorix.audiorouter.common.audio.AudioBufferPool
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/** Consumer of captured PCM frames. */
public interface FrameSink {
    /**
     * Called for every successful `AudioRecord.read()`. Implementations
     * MUST copy the bytes if they need them past return — the buffer is
     * pooled and may be reused on the next read.
     */
    public fun onFrameCaptured(
        pcm: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
        captureEpochMs: Long,
    )
}

/** Snapshot of capture engine telemetry. */
public data class CaptureEngineState(
    public val running: Boolean,
    public val paused: Boolean,
    public val framesRead: Long,
    public val bytesRead: Long,
    public val underrunCount: Long,
    public val lastReadEpochMs: Long,
    public val lastErrorMessage: String?,
)

/** Outcome of [PlaybackCaptureEngine.start]. */
public sealed interface CaptureStartResult {
    public object Started : CaptureStartResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : CaptureStartResult
}

/**
 * The capture engine. One instance per service; reusable — call [start]
 * with a fresh AudioRecord to begin capture, [stop] to tear down.
 */
public class PlaybackCaptureEngine(
    private val scope: CoroutineScope,
    private val frameSink: FrameSink,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferPool: AudioBufferPool? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val recordRef: AtomicReference<AudioRecord?> = AtomicReference(null)
    private val captureJob: AtomicReference<Job?> = AtomicReference(null)
    private val running: AtomicBoolean = AtomicBoolean(false)
    private val paused: AtomicBoolean = AtomicBoolean(false)
    private val framesRead: AtomicLong = AtomicLong(0L)
    private val bytesRead: AtomicLong = AtomicLong(0L)
    private val underrunCount: AtomicLong = AtomicLong(0L)
    private val lastReadEpochMs: AtomicLong = AtomicLong(0L)
    private val lastErrorMessage: AtomicReference<String?> = AtomicReference(null)
    private val activeConfig: AtomicReference<AudioCaptureConfig?> = AtomicReference(null)

    /** Snapshot the engine's current state. */
    public fun snapshot(): CaptureEngineState =
        CaptureEngineState(
            running = running.get(),
            paused = paused.get(),
            framesRead = framesRead.get(),
            bytesRead = bytesRead.get(),
            underrunCount = underrunCount.get(),
            lastReadEpochMs = lastReadEpochMs.get(),
            lastErrorMessage = lastErrorMessage.get(),
        )

    /**
     * Start the capture loop against the supplied record. Idempotent —
     * calling start twice with the same record is a no-op and returns
     * Started.
     */
    public fun start(record: AudioRecord, config: AudioCaptureConfig): CaptureStartResult {
        val existing = recordRef.get()
        if (existing != null) {
            if (existing === record) return CaptureStartResult.Started
            DaemonLogger.get().warn(TAG, "engine.start.replaced_existing")
            stopInternal(existing)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            DaemonLogger.get().error(
                TAG,
                "engine.start.failed reason=uninitialised state=${record.state}",
            )
            return CaptureStartResult.Failed("record_uninitialised")
        }
        try {
            record.startRecording()
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "engine.start.failed phase=startRecording err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return CaptureStartResult.Failed("start_recording_threw", t)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            DaemonLogger.get().error(
                TAG,
                "engine.start.failed reason=not_recording state=${record.recordingState}",
            )
            runCatching { record.stop() }
            return CaptureStartResult.Failed("record_not_recording_after_start")
        }
        recordRef.set(record)
        activeConfig.set(config)
        running.set(true)
        paused.set(false)
        lastErrorMessage.set(null)
        val pool = bufferPool ?: AudioBufferPool(bufferSize = config.bytesPerScheduledRead)
        val job = scope.launch(captureDispatcher) {
            captureLoop(record = record, config = config, pool = pool)
        }
        captureJob.set(job)
        DaemonLogger.get().info(
            TAG,
            "engine.start.success rateHz=${config.sampleRateHz} ch=${config.channelCount} bytesPerRead=${config.bytesPerScheduledRead}",
        )
        return CaptureStartResult.Started
    }

    /** Pause without releasing the AudioRecord. Resume is instant. */
    public fun pause() {
        if (paused.compareAndSet(false, true)) {
            DaemonLogger.get().info(TAG, "engine.pause")
        }
    }

    /** Resume PCM reads. */
    public fun resume() {
        if (paused.compareAndSet(true, false)) {
            DaemonLogger.get().info(TAG, "engine.resume")
        }
    }

    /** Stop and release the AudioRecord. The engine is reusable after this. */
    public fun stop() {
        val record = recordRef.getAndSet(null) ?: return
        stopInternal(record)
    }

    private fun stopInternal(record: AudioRecord) {
        running.set(false)
        paused.set(false)
        val job = captureJob.getAndSet(null)
        if (job != null) {
            // Best-effort cancel-and-join; capture coroutine is finite + co-operative.
            try {
                runBlocking { job.cancelAndJoin() }
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "engine.stop.join_failed err=${t.javaClass.simpleName}",
                )
            }
        }
        try {
            record.stop()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "engine.stop.record_stop_failed err=${t.javaClass.simpleName}",
            )
        }
        try {
            record.release()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "engine.stop.record_release_failed err=${t.javaClass.simpleName}",
            )
        }
        activeConfig.set(null)
        DaemonLogger.get().info(TAG, "engine.stop.complete")
    }

    private suspend fun captureLoop(
        record: AudioRecord,
        config: AudioCaptureConfig,
        pool: AudioBufferPool,
    ) {
        val readBytes = config.bytesPerScheduledRead
        while (scope.isActive && running.get()) {
            if (paused.get()) {
                // Yield while paused so callers can resume without us hammering.
                delay(PAUSE_POLL_INTERVAL_MS)
                continue
            }
            val buffer = pool.acquire()
            val n = try {
                record.read(buffer, 0, readBytes)
            } catch (t: Throwable) {
                lastErrorMessage.set("read_threw:${t.javaClass.simpleName}")
                DaemonLogger.get().warn(
                    TAG,
                    "engine.read.threw err=${t.javaClass.simpleName} msg=${t.message}",
                )
                pool.release(buffer)
                break
            }
            when {
                n > 0 -> {
                    framesRead.incrementAndGet()
                    bytesRead.addAndGet(n.toLong())
                    val now = clock()
                    lastReadEpochMs.set(now)
                    try {
                        frameSink.onFrameCaptured(
                            pcm = buffer,
                            offsetBytes = 0,
                            lengthBytes = n,
                            captureEpochMs = now,
                        )
                    } catch (t: Throwable) {
                        lastErrorMessage.set("sink_threw:${t.javaClass.simpleName}")
                        DaemonLogger.get().warn(
                            TAG,
                            "engine.sink.threw err=${t.javaClass.simpleName} msg=${t.message}",
                        )
                    }
                    pool.release(buffer)
                    if (n < readBytes) {
                        underrunCount.incrementAndGet()
                    }
                }
                n == 0 -> {
                    // Zero-length read — likely no audio yet. Yield and try again.
                    pool.release(buffer)
                    yield()
                }
                else -> {
                    // Negative return codes: ERROR_INVALID_OPERATION, ERROR_BAD_VALUE, ERROR_DEAD_OBJECT, ERROR.
                    underrunCount.incrementAndGet()
                    lastErrorMessage.set("read_returned:$n")
                    DaemonLogger.get().warn(TAG, "engine.read.failed code=$n")
                    pool.release(buffer)
                    // Don't tight-loop on a sustained error — break out so
                    // CaptureRecoveryEngine can take over.
                    break
                }
            }
        }
        DaemonLogger.get().info(
            TAG,
            "engine.loop.exit framesRead=${framesRead.get()} bytesRead=${bytesRead.get()} underrun=${underrunCount.get()}",
        )
    }

    public companion object {
        private const val TAG: String = "PlaybackCaptureEngine"
        private const val PAUSE_POLL_INTERVAL_MS: Long = 50L
    }
}

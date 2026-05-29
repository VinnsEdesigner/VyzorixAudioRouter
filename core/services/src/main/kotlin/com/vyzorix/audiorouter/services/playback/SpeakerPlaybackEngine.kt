// SpeakerPlaybackEngine — sub-millisecond playback loop that reads from
// the native ring buffer and writes to the AudioTrack.
//
// Flow:
//   1. Caller starts the engine; we build an AudioTrack via [AudioTrackFactory]
//      with USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH.
//   2. The capture side enqueues PCM bytes via [enqueue]. We hold a
//      bounded queue of frames (FIFO).
//   3. A coroutine on [playbackDispatcher] pulls frames and writes them
//      to AudioTrack in blocking mode (the system will park us on
//      buffer-full).
//   4. On capture starvation, [UnderrunRecovery] injects silence.
//   5. Each write latency is fed to [LatencyOptimizer] for dynamic buffer
//      sizing decisions.
//
// Why a bounded queue rather than reading directly from the native ring
// buffer: the native ring buffer is for the C++ DSP path (Layer 2+);
// crossing the JNI boundary every 20 ms costs more than the queue
// overhead. Layer 5+ can rewire this to read directly if profiling
// proves it's worth it.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §5.1 +
// doc/MEDIA_PROJECTION_FLOW.md §Phase 4.

package com.vyzorix.audiorouter.services.playback

import com.vyzorix.audiorouter.services.capture.FrameSink
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Snapshot of the engine's playback state. */
public data class PlaybackEngineState(
    public val running: Boolean,
    public val queuedFrames: Int,
    public val framesWritten: Long,
    public val bytesWritten: Long,
    public val droppedFrames: Long,
    public val lastWriteEpochMs: Long,
)

/** Outcome of [SpeakerPlaybackEngine.start]. */
public sealed interface PlaybackStartResult {
    public object Started : PlaybackStartResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : PlaybackStartResult
}

/**
 * Speaker playback engine. Implements [FrameSink] so the capture side
 * can feed it directly without an adapter.
 *
 * Single-instance per service; reuseable after [stop].
 */
public class SpeakerPlaybackEngine(
    private val scope: CoroutineScope,
    private val trackFactory: AudioTrackFactory,
    private val underrunRecovery: UnderrunRecovery,
    private val latencyOptimizer: LatencyOptimizer,
    private val trackController: AudioTrackController = AudioTrackController(),
    private val playbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val frameQueueCapacity: Int = DEFAULT_FRAME_QUEUE_CAPACITY,
    private val nowMicros: () -> Long = { System.nanoTime() / 1_000L },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FrameSink, RecoveryObserver {

    private val queue: ArrayBlockingQueue<PcmFrame> = ArrayBlockingQueue(frameQueueCapacity)
    private val running: AtomicBoolean = AtomicBoolean(false)
    private val playbackJob: AtomicReference<Job?> = AtomicReference(null)
    private val framesWritten: AtomicLong = AtomicLong(0L)
    private val bytesWritten: AtomicLong = AtomicLong(0L)
    private val droppedFrames: AtomicLong = AtomicLong(0L)
    private val lastWriteEpochMs: AtomicLong = AtomicLong(0L)
    private val trackConfigRef: AtomicReference<AudioTrackConfig> = AtomicReference(AudioTrackConfig())

    /** Expose the controller so RouteRecoveryEngine + PlaybackGainController can call into it. */
    public val controller: AudioTrackController get() = trackController

    /** Internal frame container. */
    private data class PcmFrame(
        val bytes: ByteArray,
        val offsetBytes: Int,
        val lengthBytes: Int,
        val captureEpochMs: Long,
    )

    /** Snapshot the engine's current state. */
    public fun snapshot(): PlaybackEngineState =
        PlaybackEngineState(
            running = running.get(),
            queuedFrames = queue.size,
            framesWritten = framesWritten.get(),
            bytesWritten = bytesWritten.get(),
            droppedFrames = droppedFrames.get(),
            lastWriteEpochMs = lastWriteEpochMs.get(),
        )

    /** Start the playback loop. Idempotent — concurrent starts are no-ops. */
    public fun start(config: AudioTrackConfig = AudioTrackConfig()): PlaybackStartResult {
        if (running.get()) return PlaybackStartResult.Started
        val buildResult = trackFactory.create(config = config)
        if (buildResult !is PlaybackTrackResult.Success) {
            DaemonLogger.get().error(
                TAG,
                "playback.start.failed " +
                    "reason=${(buildResult as PlaybackTrackResult.Failed).reason}",
            )
            return PlaybackStartResult.Failed(buildResult.reason, buildResult.cause)
        }
        val mounted = trackController.mount(buildResult.track)
        if (mounted is MountResult.Rejected) {
            DaemonLogger.get().error(
                TAG,
                "playback.start.failed phase=mount reason=${mounted.reason}",
            )
            buildResult.track.release()
            return PlaybackStartResult.Failed("track_controller_rejected_${mounted.reason}")
        }
        if (!trackController.play()) {
            DaemonLogger.get().error(TAG, "playback.start.failed phase=play")
            trackController.releaseAndUnmount()
            return PlaybackStartResult.Failed("audio_track_play_threw")
        }
        trackConfigRef.set(config)
        running.set(true)
        val job = scope.launch(playbackDispatcher) {
            playbackLoop(config = config)
        }
        playbackJob.set(job)
        DaemonLogger.get().info(
            TAG,
            "playback.start.success rateHz=${config.sampleRateHz} ch=${config.channelCount}",
        )
        return PlaybackStartResult.Started
    }

    /** Stop the playback loop and release the AudioTrack. */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        val job = playbackJob.getAndSet(null)
        if (job != null) {
            try {
                runBlocking { job.cancelAndJoin() }
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "playback.stop.join_failed err=${t.javaClass.simpleName}",
                )
            }
        }
        trackController.stop()
        trackController.releaseAndUnmount()
        queue.clear()
        DaemonLogger.get().info(TAG, "playback.stop.complete")
    }

    /** RouteRecoveryEngine — invoked AFTER a fresh AudioTrack has been mounted. */
    public override fun onRouteRebuilt(track: PlaybackTrackResult.Success) {
        framesWritten.set(0L)
        bytesWritten.set(0L)
        // Drop any frames buffered against the old route — they were captured
        // when the speaker was off the bus and would produce a transient hop.
        queue.clear()
        DaemonLogger.get().info(
            TAG,
            "playback.route.rebuilt queue_cleared=true",
        )
    }

    /** RouteRecoveryEngine — invoked when the rebuild failed. */
    public override fun onRouteRebuildFailed(reason: String) {
        DaemonLogger.get().warn(TAG, "playback.route.rebuild_failed reason=$reason")
    }

    // FrameSink — capture engine forwards bytes here.
    public override fun onFrameCaptured(
        pcm: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
        captureEpochMs: Long,
    ) {
        if (!running.get()) return
        // Copy because the pool may reuse the array on the next read.
        val copy = ByteArray(lengthBytes)
        System.arraycopy(pcm, offsetBytes, copy, 0, lengthBytes)
        val frame = PcmFrame(
            bytes = copy,
            offsetBytes = 0,
            lengthBytes = lengthBytes,
            captureEpochMs = captureEpochMs,
        )
        if (!queue.offer(frame)) {
            droppedFrames.incrementAndGet()
            latencyOptimizer.recordOverrun()
            if (droppedFrames.get() % LOG_DROP_EVERY_N == 0L) {
                DaemonLogger.get().warn(
                    TAG,
                    "playback.frame.dropped total=${droppedFrames.get()} queueCapacity=$frameQueueCapacity",
                )
            }
        }
    }

    private suspend fun playbackLoop(config: AudioTrackConfig) {
        val pollIntervalMs = POLL_INTERVAL_MS
        while (scope.isActive && running.get()) {
            val frame = queue.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
            if (frame != null) {
                val start = nowMicros()
                val writeOutcome = trackController.write(
                    frame.bytes,
                    frame.offsetBytes,
                    frame.lengthBytes,
                )
                val written: Int = when (writeOutcome) {
                    is ControllerWriteResult.Wrote -> writeOutcome.bytesWritten
                    is ControllerWriteResult.NotMounted -> 0
                    is ControllerWriteResult.Failed -> {
                        DaemonLogger.get().warn(
                            TAG,
                            "playback.write.failed code=${writeOutcome.errorCode}",
                        )
                        -1
                    }
                }
                if (written < 0) break
                if (written > 0) {
                    framesWritten.incrementAndGet()
                    bytesWritten.addAndGet(written.toLong())
                    lastWriteEpochMs.set(clock())
                    val elapsedMicros = nowMicros() - start
                    latencyOptimizer.recordWriteLatency(elapsedMicros)
                }
                if (written in 0 until frame.lengthBytes) {
                    latencyOptimizer.recordUnderrun()
                }
            } else {
                // No captured frame ready — inject silence to keep the
                // track from underrunning on its own.
                val decision = underrunRecovery.advise(
                    availableCapturedBytes = 0,
                    requestedBytes = config.bufferMultiplier * SILENCE_GRANULE_BYTES,
                )
                if (decision is UnderrunDecision.InjectSilence) {
                    val start = nowMicros()
                    val writeOutcome = trackController.write(
                        decision.silenceBytes,
                        0,
                        decision.silenceLengthBytes,
                    )
                    val written: Int = when (writeOutcome) {
                        is ControllerWriteResult.Wrote -> writeOutcome.bytesWritten
                        is ControllerWriteResult.NotMounted -> 0
                        is ControllerWriteResult.Failed -> -1
                    }
                    if (written > 0) {
                        val elapsedMicros = nowMicros() - start
                        latencyOptimizer.recordWriteLatency(elapsedMicros)
                    }
                }
            }
        }
        DaemonLogger.get().info(
            TAG,
            "playback.loop.exit framesWritten=${framesWritten.get()} bytesWritten=${bytesWritten.get()} dropped=${droppedFrames.get()}",
        )
    }

    public companion object {
        public const val DEFAULT_FRAME_QUEUE_CAPACITY: Int = 64
        private const val POLL_INTERVAL_MS: Long = 20L
        private const val SILENCE_GRANULE_BYTES: Int = 256
        private const val LOG_DROP_EVERY_N: Long = 50L
        private const val TAG: String = "SpeakerPlaybackEngine"
    }
}

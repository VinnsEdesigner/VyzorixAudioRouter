// AudioPipelineController — coordinates [AudioPipeline] startup / shutdown
// from a service or Kotlin coroutine context.
//
// Layer 2 landed the controller skeleton — start/stop/sample helpers.
// Layer 4 fills in the body with:
//   - [feedCapturedFrame]: push PCM bytes into the native ring buffer
//     (called by Layer 4's PlaybackCaptureEngine via its FrameSink).
//   - [pullPlaybackFrame]: pop PCM bytes out of the native ring buffer
//     (called by Layer 4's SpeakerPlaybackEngine when it needs to mix
//     captured-vs-silence on the playback side).
//   - [ringBufferHandle]: exposed so higher layers can poke the native
//     ring buffer directly for telemetry (`availableRead`/`availableWrite`).
//
// Threading: all methods are safe to call from any thread. The native
// ring buffer is a lock-free SPSC; with one producer (capture thread)
// and one consumer (playback thread) we don't need additional locking.

package com.vyzorix.audiorouter.audioengine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thin orchestration layer over [AudioPipeline] so Layer 3+ services
 * (`PersistentAudioService`) can reason about the pipeline without owning
 * the JNI handle directly.
 */
public class AudioPipelineController(
    private val pipeline: AudioPipeline = AudioPipeline(),
) {
    private val _events: MutableSharedFlow<AudioEngineHealthState> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 8)

    /** Snapshot stream of the engine telemetry. */
    public val events: Flow<AudioEngineHealthState> = _events.asSharedFlow()

    public val state: PipelineStateTracker get() = pipeline.state
    public val safety: NativeSafetyController get() = pipeline.nativeSafety

    /**
     * Native ring buffer handle for the active pipeline session. Returns
     * `0L` when the pipeline is not currently started.
     */
    public val ringBufferHandle: Long
        get() = pipeline.activeRingBufferHandle

    public fun start(): AudioPipelineStartResult {
        val result = pipeline.start()
        _events.tryEmit(pipeline.sampleHealth())
        return result
    }

    public fun stop() {
        pipeline.stop()
        _events.tryEmit(AudioEngineHealthState.Unavailable)
    }

    public fun pollHealth(): AudioEngineHealthState {
        val health = pipeline.sampleHealth()
        _events.tryEmit(health)
        return health
    }

    /**
     * Feed PCM bytes into the native ring buffer. Returns the number of
     * bytes actually written; may be less than [lengthBytes] if the buffer
     * is full (overrun counter is incremented in that case).
     *
     * Layer 4 callers MUST check the return value and decide whether to
     * drop the frame or back off.
     */
    public fun feedCapturedFrame(
        pcm: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
    ): Int {
        val handle = pipeline.activeRingBufferHandle
        if (handle == 0L) return 0
        return pipeline.bridgeWrite(
            handle = handle,
            src = pcm,
            offsetBytes = offsetBytes,
            lengthBytes = lengthBytes,
        )
    }

    /**
     * Pull PCM bytes out of the native ring buffer. Returns the number of
     * bytes actually read; short reads bump the underrun counter and the
     * caller should request silence injection for the gap.
     */
    public fun pullPlaybackFrame(
        dst: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
    ): Int {
        val handle = pipeline.activeRingBufferHandle
        if (handle == 0L) return 0
        return pipeline.bridgeRead(
            handle = handle,
            dst = dst,
            offsetBytes = offsetBytes,
            lengthBytes = lengthBytes,
        )
    }

    /** Number of bytes currently available to read (== buffered capture). */
    public fun availableReadBytes(): Int {
        val handle = pipeline.activeRingBufferHandle
        if (handle == 0L) return 0
        return pipeline.bridgeAvailableReadBytes(handle = handle)
    }
}

// AudioPipelineController — coordinates [AudioPipeline] startup / shutdown
// from a service or Kotlin coroutine context.
//
// Layer 2 lands the controller skeleton — start/stop/sample helpers. Layer 3
// will plug in the capture and playback coroutine jobs.

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
}

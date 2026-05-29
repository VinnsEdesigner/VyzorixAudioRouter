// PipelineStateTracker — observable state machine for the Kotlin audio
// pipeline (capture → ring buffer → playback). Layer 2 lands the contract;
// Layer 3 wires it into AudioPipelineController.

package com.vyzorix.audiorouter.audioengine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public enum class PipelineState {
    /** Pre-init: bridge loaded but no ring buffer / threads yet. */
    Idle,

    /** Allocating the ring buffer, raising thread priority. */
    Initializing,

    /** Capture and playback loops are running. */
    Streaming,

    /** Pipeline is alive but the audio loop is paused (focus loss, etc.). */
    Paused,

    /**
     * Unrecoverable native failure observed via
     * [NativeAudioBridge.pollCrashGuard]; pipeline must be torn down before
     * use can resume.
     */
    Error,
}

/**
 * Single-writer state holder for the pipeline state machine. The audio
 * thread updates the state; UI / Diagnostics consume via [state].
 */
public class PipelineStateTracker {
    private val _state: MutableStateFlow<PipelineState> = MutableStateFlow(PipelineState.Idle)

    public val state: StateFlow<PipelineState> = _state.asStateFlow()

    public fun update(next: PipelineState) {
        _state.value = next
    }
}

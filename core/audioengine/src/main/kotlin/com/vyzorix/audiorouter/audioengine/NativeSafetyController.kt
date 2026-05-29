// NativeSafetyController — receives crash-guard / underrun signals from the
// native engine and decides whether to fall back to the Java-only audio
// pipeline.
//
// Layer 2 lands the controller surface; Layer 3 wires it into
// AudioPipelineController so the dashboard can render the fallback state
// distinctly.

package com.vyzorix.audiorouter.audioengine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides whether to keep using the native engine or fall back to the
 * `audio_fallback_bridge` Java-only path.
 *
 * Layer 6+ surfaces the [decision] state to the dashboard so the operator
 * knows whether the C22 is currently running on:
 *  - "Audio: real-time native" (best case)
 *  - "Audio: best-effort native" (Unisoc SCHED_FIFO silent fallback)
 *  - "Audio: java-only" (native engine crashed or unavailable)
 */
public class NativeSafetyController {

    public enum class Decision {
        /** Use the native engine with SCHED_FIFO priority. */
        NativeRealTime,
        /** Use the native engine without real-time priority. */
        NativeBestEffort,
        /** Fall back to the Java-only audio path. */
        JavaFallback,
    }

    private val _decision: MutableStateFlow<Decision> =
        MutableStateFlow(Decision.JavaFallback)

    public val decision: StateFlow<Decision> = _decision.asStateFlow()

    /**
     * Update the decision based on the latest engine telemetry. Idempotent;
     * the underlying StateFlow only emits when the value changes.
     */
    public fun reconsider(health: AudioEngineHealthState) {
        val next = when {
            !health.isNativeAvailable -> Decision.JavaFallback
            health.lastCrashSignal != NativeAudioBridge.CrashGuardSignal.None -> Decision.JavaFallback
            health.priority == NativeAudioBridge.PriorityResult.RealTime -> Decision.NativeRealTime
            else -> Decision.NativeBestEffort
        }
        _decision.value = next
    }
}

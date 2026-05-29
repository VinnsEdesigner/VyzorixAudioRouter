// AudioEngineHealthState — immutable telemetry snapshot of the native
// engine. Surfaced to Layer 6's `RuntimeEventTimeline` + the dashboard.
//
// Layer 2 lands the type definitions; Layer 3 populates them via the JNI
// bridge counters.

package com.vyzorix.audiorouter.audioengine

/**
 * Immutable telemetry snapshot of the native audio engine.
 *
 * Captured periodically (every ~500 ms in Layer 3+) so the dashboard can
 * surface "live audio" vs "fallback" status without polling the JNI bridge
 * on every render.
 */
public data class AudioEngineHealthState(
    /** True if `libaudioengine.so` is loaded and ready to receive calls. */
    public val isNativeAvailable: Boolean,
    /** Outcome of the last `elevatePriority` attempt. */
    public val priority: NativeAudioBridge.PriorityResult,
    /** Most recent fatal signal observed by `crash_guard.cpp`. */
    public val lastCrashSignal: NativeAudioBridge.CrashGuardSignal,
    /** Frames currently held in the ring buffer. */
    public val ringBufferFramesUsed: Int,
    /** Frames available to write before overrun. */
    public val ringBufferFramesFree: Int,
    /**
     * Buffer pressure in basis points (0..10000). > 8000 (80%) triggers
     * Layer 3's backpressure controller to drop the oldest frame.
     */
    public val ringBufferPressureBp: Int,
    /** Total underrun events since the engine was loaded. */
    public val underrunCount: Long,
    /** Total overrun events since the engine was loaded. */
    public val overrunCount: Long,
    /** Live bytes tracked by `memory_guard.cpp`. */
    public val liveBytes: Long,
    /** Peak live bytes observed since the engine was loaded. */
    public val peakLiveBytes: Long,
    /** Build identifier from `nativeEngineVersion`. */
    public val engineVersion: String,
) {
    public companion object {
        /** "Engine unavailable" sentinel, used by the fallback path. */
        public val Unavailable: AudioEngineHealthState =
            AudioEngineHealthState(
                isNativeAvailable = false,
                priority = NativeAudioBridge.PriorityResult.BestEffort,
                lastCrashSignal = NativeAudioBridge.CrashGuardSignal.None,
                ringBufferFramesUsed = 0,
                ringBufferFramesFree = 0,
                ringBufferPressureBp = 0,
                underrunCount = 0L,
                overrunCount = 0L,
                liveBytes = 0L,
                peakLiveBytes = 0L,
                engineVersion = NativeAudioBridge.UNAVAILABLE_VERSION,
            )
    }
}

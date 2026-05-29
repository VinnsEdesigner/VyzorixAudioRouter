// AudioPipeline — public façade over the native engine + Kotlin scaffolding
// for Layer 3 to consume.
//
// Layer 2 lands the types + initialisation/teardown of the *native* side.
// Layer 3 will wire the actual capture/playback threads. Right now, calling
// [start] on the pipeline:
//   1. Loads `libaudioengine.so` via [NativeLoader].
//   2. Initialises the native engine (crash-guard install, logger init).
//   3. Allocates a ring buffer at [Config.ringBufferFrames] capacity.
//   4. Elevates the calling thread to SCHED_FIFO @ [Config.threadPriority]
//      and surfaces the outcome via the [NativeSafetyController].
//
// It does NOT yet:
//   * Open an AudioRecord (Layer 3).
//   * Open an AudioTrack (Layer 3).
//   * Run the capture/playback loop (Layer 3).
//
// This is intentional and matches `doc/BUILD_ORDER.md` §Layer 2: the JNI
// surface must be callable and the ring buffer round-trip must work, but
// audio doesn't physically flow until Layer 3.

package com.vyzorix.audiorouter.audioengine

/**
 * Pipeline-wide configuration. The defaults match the Nokia C22 budget
 * from `doc/AUDIO_LATENCY_BUDGETS.md`.
 */
public data class AudioPipelineConfig(
    /** Power-of-two ring buffer capacity, in mono S16LE frames. */
    public val ringBufferFrames: Int = DEFAULT_RING_BUFFER_FRAMES,
    /**
     * SCHED_FIFO priority for the audio thread. Per
     * `doc/NOKIA_C22_NOTES.md` §2.3 the kernel may silently downgrade
     * this to SCHED_OTHER; the [NativeSafetyController] surfaces that.
     */
    public val threadPriority: Int = DEFAULT_THREAD_PRIORITY,
) {
    init {
        require(ringBufferFrames > 0) { "ringBufferFrames must be > 0: $ringBufferFrames" }
        require(ringBufferFrames and (ringBufferFrames - 1) == 0) {
            "ringBufferFrames must be a power of two (got $ringBufferFrames)"
        }
    }

    public companion object {
        public const val DEFAULT_RING_BUFFER_FRAMES: Int = 32_768
        public const val DEFAULT_THREAD_PRIORITY: Int = 5
    }
}

/**
 * Outcome of [AudioPipeline.start].
 */
public sealed interface AudioPipelineStartResult {
    public object Success : AudioPipelineStartResult
    public data class NativeUnavailable(public val cause: Throwable?) : AudioPipelineStartResult
    public object RingBufferAllocationFailed : AudioPipelineStartResult
}

/**
 * Layer 2's audio pipeline façade. Stateful; one instance per pipeline.
 * Layer 3 will spin up capture + playback threads against this surface.
 */
public class AudioPipeline(
    public val config: AudioPipelineConfig = AudioPipelineConfig(),
    private val bridge: NativeAudioBridge = NativeAudioBridge,
    private val safety: NativeSafetyController = NativeSafetyController(),
    private val stateTracker: PipelineStateTracker = PipelineStateTracker(),
) {
    @Volatile
    private var ringBufferHandle: Long = 0L

    /** Most recently observed engine telemetry. */
    @Volatile
    private var lastHealth: AudioEngineHealthState = AudioEngineHealthState.Unavailable

    public val state: PipelineStateTracker get() = stateTracker
    public val nativeSafety: NativeSafetyController get() = safety

    /**
     * Idempotent: calling [start] twice on the same pipeline returns
     * [AudioPipelineStartResult.Success] for the second call.
     */
    public fun start(): AudioPipelineStartResult {
        if (ringBufferHandle != 0L) {
            return AudioPipelineStartResult.Success
        }
        stateTracker.update(PipelineState.Initializing)
        if (!bridge.isAvailable) {
            stateTracker.update(PipelineState.Error)
            val snapshot = NativeLoader.snapshot() as? NativeLoader.LoadState.Failed
            return AudioPipelineStartResult.NativeUnavailable(cause = snapshot?.cause)
        }
        bridge.ensureInitialised()
        val handle = bridge.allocateRingBuffer(capacityFrames = config.ringBufferFrames)
        if (handle == 0L) {
            stateTracker.update(PipelineState.Error)
            return AudioPipelineStartResult.RingBufferAllocationFailed
        }
        ringBufferHandle = handle
        val priority = bridge.elevatePriority(config.threadPriority)
        // Layer 3 will set state to Streaming once the capture loop is alive;
        // for now we stay in Initializing until the consumer-side wiring lands.
        lastHealth = buildHealth(handle = handle, priority = priority)
        safety.reconsider(lastHealth)
        return AudioPipelineStartResult.Success
    }

    /**
     * Tears down the pipeline. Idempotent; calling [stop] on a non-started
     * pipeline is a no-op.
     */
    public fun stop() {
        val handle = ringBufferHandle
        if (handle != 0L) {
            bridge.releaseRingBuffer(handle)
            ringBufferHandle = 0L
        }
        if (bridge.isAvailable) {
            bridge.restorePriority()
        }
        stateTracker.update(PipelineState.Idle)
    }

    /**
     * Sample the latest engine telemetry. Called periodically by Layer 3's
     * health reporter (every ~500 ms). Returns the cached snapshot when the
     * native engine is unavailable.
     */
    public fun sampleHealth(): AudioEngineHealthState {
        val handle = ringBufferHandle
        if (handle == 0L || !bridge.isAvailable) {
            return lastHealth
        }
        // Re-query the bridge each time; the values change every audio chunk.
        val current = buildHealth(handle = handle, priority = lastHealth.priority)
        lastHealth = current
        safety.reconsider(current)
        return current
    }

    /** Native ring buffer handle; only valid while the pipeline is started. */
    public fun ringBufferHandle(): Long = ringBufferHandle

    private fun buildHealth(handle: Long, priority: NativeAudioBridge.PriorityResult): AudioEngineHealthState {
        val used = bridge.availableRead(handle)
        val free = bridge.availableWrite(handle)
        val capacity = used + free
        val pressureBp = if (capacity > 0) (used.toLong() * 10_000L / capacity.toLong()).toInt() else 0
        return AudioEngineHealthState(
            isNativeAvailable = bridge.isAvailable,
            priority = priority,
            lastCrashSignal = bridge.pollCrashGuard(),
            ringBufferFramesUsed = used,
            ringBufferFramesFree = free,
            ringBufferPressureBp = pressureBp,
            underrunCount = bridge.underrunCount(handle),
            overrunCount = bridge.overrunCount(handle),
            liveBytes = bridge.liveBytes(),
            peakLiveBytes = bridge.peakLiveBytes(),
            engineVersion = bridge.engineVersion(),
        )
    }
}

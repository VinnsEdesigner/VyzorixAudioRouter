// PipelineBackpressureController — when the consumer (playback) falls
// behind the producer (capture), this controller decides whether to drop
// the oldest frame to keep latency bounded.
//
// Layer 2 lands the policy; Layer 3 invokes it from the audio loop.

package com.vyzorix.audiorouter.audioengine

/**
 * Decides whether the audio loop should drop the oldest frame in the
 * ring buffer to bound end-to-end latency.
 *
 * Policy (Layer 2 default):
 *   * If buffer pressure is <= 80% (8000 basis points), keep all frames.
 *   * If buffer pressure exceeds 80%, drop until below 60% (6000 bp).
 *
 * The hysteresis prevents oscillating drop / keep decisions at the
 * boundary. Numbers are derived from `doc/AUDIO_LATENCY_BUDGETS.md`.
 */
public class PipelineBackpressureController(
    /** Drop frames if pressure exceeds this threshold (basis points). */
    public val highWaterBp: Int = DEFAULT_HIGH_WATER_BP,
    /** Stop dropping once pressure falls below this threshold (basis points). */
    public val lowWaterBp: Int = DEFAULT_LOW_WATER_BP,
) {
    init {
        require(highWaterBp in 0..10_000) { "highWaterBp must be in [0, 10000]: $highWaterBp" }
        require(lowWaterBp in 0..10_000) { "lowWaterBp must be in [0, 10000]: $lowWaterBp" }
        require(lowWaterBp <= highWaterBp) { "lowWaterBp must be <= highWaterBp" }
    }

    private var dropping: Boolean = false

    /**
     * @return `true` if the caller should drop the oldest frame *now*.
     */
    public fun shouldDrop(pressureBp: Int): Boolean {
        if (!dropping && pressureBp >= highWaterBp) {
            dropping = true
        } else if (dropping && pressureBp <= lowWaterBp) {
            dropping = false
        }
        return dropping
    }

    /** True if the controller is currently in the "dropping" state. */
    public fun isDropping(): Boolean = dropping

    public companion object {
        public const val DEFAULT_HIGH_WATER_BP: Int = 8000
        public const val DEFAULT_LOW_WATER_BP: Int = 6000
    }
}

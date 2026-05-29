// PlaybackGainController — volume normalisation policy for the
// daemon's AudioTrack.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 639:
//     core/services/playback/PlaybackGainController.kt
//       "Volume normalization; prevents speaker clipping".
//
// The Nokia C22's built-in speaker clips audibly above ~85% AudioTrack
// gain when the source is anything other than CONTENT_TYPE_SPEECH. To
// avoid speaker distortion (and the user's reflex of "the speaker is
// broken!"), we cap the post-VoIP gain at 0.85 by default. The cap is
// applied AFTER any normalisation the source app already performed —
// per DOC_3 §5.6 we do not attempt full RMS-based loudness normalisation
// at this layer, only saturation protection.
//
// Three-tier policy:
//   1. NORMAL (default): write [normalGain] (= 0.85f). Most playback.
//   2. ATTENUATED: write [attenuatedGain] (= 0.50f). Triggered by the
//      thermal signal hitting HIGH or the headset-hijack recovery storm
//      injecting silence over a degraded route.
//   3. MUTED: write 0.0f. Used by EmergencyStopAction during safe-mode
//      shutdown so the speaker doesn't pop during the AudioTrack tear-down.
//
// The controller exposes a single `apply()` method that the engine calls
// AFTER any controller.mount(). Subsequent state transitions are pushed
// via [setMode].
//
// Threading: all mutations are CAS on AtomicReference. Reads are lock-free.

package com.vyzorix.audiorouter.services.playback

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference

/** Discrete gain tiers the controller can be in. */
public enum class GainMode {
    /** Normal playback — capped slightly below 1.0 to prevent C22 speaker clipping. */
    NORMAL,

    /** Reduced gain for thermal / recovery scenarios. */
    ATTENUATED,

    /** Hard mute for safe-mode shutdown. */
    MUTED,
}

/** Reason supplied to [PlaybackGainController.setMode] for forensics. */
public data class GainTransitionContext(
    public val reason: String,
    public val source: String,
)

/** Diagnostic snapshot for the dashboard. */
public data class PlaybackGainSnapshot(
    public val mode: GainMode,
    public val effectiveGain: Float,
    public val transitions: Long,
    public val lastTransitionEpochMs: Long,
    public val lastReason: String,
    public val lastSource: String,
)

/**
 * Decides the effective gain to write to [AudioTrackController.setVolume].
 *
 * Single-instance per playback engine. Reads/writes are atomic so the
 * route-war thread and the dashboard thread can both query state
 * without synchronisation.
 */
public class PlaybackGainController(
    private val controller: AudioTrackController,
    private val normalGain: Float = DEFAULT_NORMAL_GAIN,
    private val attenuatedGain: Float = DEFAULT_ATTENUATED_GAIN,
    private val mutedGain: Float = 0.0f,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        require(normalGain in 0.0f..1.0f) {
            "normalGain must be in [0.0, 1.0] (got $normalGain)"
        }
        require(attenuatedGain in 0.0f..normalGain) {
            "attenuatedGain must be in [0.0, normalGain] (got $attenuatedGain)"
        }
        require(mutedGain == 0.0f) {
            "mutedGain must be exactly 0.0 (got $mutedGain)"
        }
    }

    private val mode: AtomicReference<GainMode> = AtomicReference(GainMode.NORMAL)
    private val transitions: AtomicReference<Long> = AtomicReference(0L)
    private val lastTransitionEpochMs: AtomicReference<Long> = AtomicReference(0L)
    private val lastReason: AtomicReference<String> = AtomicReference("init")
    private val lastSource: AtomicReference<String> = AtomicReference("init")

    /** Apply the current mode's gain to the controller. Returns true on success. */
    public fun apply(): Boolean {
        val gain = effectiveGain()
        val ok = controller.setVolume(clamp(gain))
        if (!ok) {
            DaemonLogger.get().warn(
                TAG,
                "gain.apply.failed mode=${mode.get()} gain=$gain",
            )
        }
        return ok
    }

    /**
     * Transition into [newMode] and apply the resulting gain. Records the
     * supplied [context] for the dashboard's "last reason" indicator.
     */
    public fun setMode(newMode: GainMode, context: GainTransitionContext): Boolean {
        val previous = mode.getAndSet(newMode)
        if (previous != newMode) {
            transitions.set(transitions.get() + 1L)
            lastTransitionEpochMs.set(clock())
            lastReason.set(context.reason)
            lastSource.set(context.source)
            DaemonLogger.get().info(
                TAG,
                "gain.mode.transition from=$previous to=$newMode " +
                    "effective=${effectiveGain()} reason=${context.reason} source=${context.source}",
            )
        }
        return apply()
    }

    /** Resolve the floating-point gain for the current mode (clamped). */
    public fun effectiveGain(): Float {
        val current = mode.get() ?: GainMode.NORMAL
        val raw = when (current) {
            GainMode.NORMAL -> normalGain
            GainMode.ATTENUATED -> attenuatedGain
            GainMode.MUTED -> mutedGain
        }
        return clamp(raw)
    }

    /** Diagnostic snapshot — safe to call from any thread. */
    public fun snapshot(): PlaybackGainSnapshot =
        PlaybackGainSnapshot(
            mode = mode.get(),
            effectiveGain = effectiveGain(),
            transitions = transitions.get(),
            lastTransitionEpochMs = lastTransitionEpochMs.get(),
            lastReason = lastReason.get(),
            lastSource = lastSource.get(),
        )

    private fun clamp(value: Float): Float = when {
        value.isNaN() -> 0.0f
        value < 0.0f -> 0.0f
        value > 1.0f -> 1.0f
        else -> value
    }

    public companion object {
        /**
         * Default ceiling for the Nokia C22 speaker. Empirically — DOC_3
         * §5.6 — anything above 0.85 produces audible clipping when the
         * source content is not voice-band. Reduced via [setMode] for
         * thermal/recovery scenarios.
         */
        public const val DEFAULT_NORMAL_GAIN: Float = 0.85f

        /** Default attenuated gain — 50%, audible but markedly quieter. */
        public const val DEFAULT_ATTENUATED_GAIN: Float = 0.50f

        private const val TAG: String = "PlaybackGainController"
    }
}

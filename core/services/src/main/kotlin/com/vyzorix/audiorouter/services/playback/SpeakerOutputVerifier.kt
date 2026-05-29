// SpeakerOutputVerifier — confirms the active audio output is the
// built-in speaker.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 640:
//     core/services/playback/SpeakerOutputVerifier.kt
//       "Verifies active output device matches built-in speaker".
//
// The route-war's central thesis (doc/VOIP_ROUTE_FORCE.md §1) is that
// MODE_IN_COMMUNICATION + setSpeakerphoneOn(true) routes audio out the
// bottom speaker. But the C22's HAL is known to lie — getSpeakerphoneOn()
// can return true while AudioPolicyManager actually has us on a phantom
// wired-headset output (see NOKIA_C22_NOTES.md §Phantom Headset).
//
// This class is the second-order confirmation: it asks
// AudioManager.getDevices(GET_DEVICES_OUTPUTS) for every active routed
// device on the AudioTrack and reports whether one of them is
// TYPE_BUILTIN_SPEAKER. RoutePersistenceDaemon polls verify() on each
// route-war tick; if it returns NotOnSpeaker the daemon kicks
// VendorRouteResetter.
//
// Note we read GET_DEVICES_OUTPUTS rather than the deprecated
// `getRoutedDevice()` — the latter returns ONE device while the daemon
// needs to detect concurrent split-routing (e.g. capture is on the
// speaker but a stale headset entry is still leaking the speech track).

package com.vyzorix.audiorouter.services.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [SpeakerOutputVerifier.verify]. */
public sealed interface SpeakerOutputVerification {
    /** Built-in speaker is among the active outputs. */
    public data class OnSpeaker(
        public val activeOutputTypes: Set<Int>,
    ) : SpeakerOutputVerification

    /**
     * Built-in speaker is NOT among the active outputs; route-war must
     * escalate. The set of types currently active is included for
     * forensic logging.
     */
    public data class NotOnSpeaker(
        public val activeOutputTypes: Set<Int>,
    ) : SpeakerOutputVerification

    /**
     * AudioManager threw — typically during a HAL reset. RoutePersistenceDaemon
     * should retry after a short delay rather than escalate.
     */
    public data class Unavailable(public val cause: Throwable) : SpeakerOutputVerification
}

/** Diagnostic snapshot of recent verification outcomes. */
public data class SpeakerOutputVerifierSnapshot(
    public val verifications: Long,
    public val onSpeakerCount: Long,
    public val notOnSpeakerCount: Long,
    public val unavailableCount: Long,
    public val lastVerifiedEpochMs: Long,
    public val lastResultLabel: String,
)

/**
 * Verifies the daemon's AudioTrack is actually being routed to the
 * built-in speaker. Stateless query each call; rolling counters track
 * historical health for the dashboard.
 */
public class SpeakerOutputVerifier(
    private val audioManager: AudioManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    public constructor(context: Context) : this(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    )

    private val verifications: AtomicLong = AtomicLong(0L)
    private val onSpeakerCount: AtomicLong = AtomicLong(0L)
    private val notOnSpeakerCount: AtomicLong = AtomicLong(0L)
    private val unavailableCount: AtomicLong = AtomicLong(0L)
    private val lastVerifiedEpochMs: AtomicLong = AtomicLong(0L)
    private val lastResultLabel: AtomicReference<String> = AtomicReference("init")

    /**
     * One-shot verification. Reads `getDevices(GET_DEVICES_OUTPUTS)` and
     * checks for `TYPE_BUILTIN_SPEAKER`.
     */
    public fun verify(): SpeakerOutputVerification {
        verifications.incrementAndGet()
        lastVerifiedEpochMs.set(clock())
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "verifier.unavailable err=${t.javaClass.simpleName} msg=${t.message}",
            )
            unavailableCount.incrementAndGet()
            lastResultLabel.set("unavailable")
            return SpeakerOutputVerification.Unavailable(t)
        }
        val types = outputs.map { it.type }.toSet()
        val onSpeaker = types.contains(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) ||
            types.contains(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
        return if (onSpeaker) {
            onSpeakerCount.incrementAndGet()
            lastResultLabel.set("on_speaker")
            SpeakerOutputVerification.OnSpeaker(types)
        } else {
            notOnSpeakerCount.incrementAndGet()
            lastResultLabel.set("not_on_speaker")
            DaemonLogger.get().warn(
                TAG,
                "verifier.not_on_speaker types=${types.joinToString(",")}",
            )
            SpeakerOutputVerification.NotOnSpeaker(types)
        }
    }

    /** Diagnostic snapshot for the dashboard. */
    public fun snapshot(): SpeakerOutputVerifierSnapshot =
        SpeakerOutputVerifierSnapshot(
            verifications = verifications.get(),
            onSpeakerCount = onSpeakerCount.get(),
            notOnSpeakerCount = notOnSpeakerCount.get(),
            unavailableCount = unavailableCount.get(),
            lastVerifiedEpochMs = lastVerifiedEpochMs.get(),
            lastResultLabel = lastResultLabel.get(),
        )

    public companion object {
        private const val TAG: String = "SpeakerOutputVerifier"
    }
}

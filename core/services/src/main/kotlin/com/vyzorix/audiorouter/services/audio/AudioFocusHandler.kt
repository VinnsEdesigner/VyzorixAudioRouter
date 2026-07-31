// AudioFocusHandler — owns the daemon's `AudioFocusRequest` lifecycle and
// surfaces focus-change events as a Flow.
//
// Layer 3 contract: the daemon holds AUDIO_FOCUS_GAIN with
// USAGE_VOICE_COMMUNICATION so that the OS treats it as a VoIP session.
// This is what lets `MODE_IN_COMMUNICATION` + `setSpeakerphoneOn(true)`
// actually take effect (see doc/VOIP_ROUTE_FORCE.md §1 "API Stack").
//
// Real phone calls / alarms / system warnings can transiently steal focus
// (AUDIOFOCUS_LOSS_*); the daemon yields by pausing the silent anchor and
// then re-grabs focus once AUDIOFOCUS_GAIN comes back.

package com.vyzorix.audiorouter.services.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A focus-state change emitted by [AudioFocusHandler.observe]. */
public sealed interface FocusEvent {
    /** Daemon now holds focus. Safe to resume / re-assert the route. */
    public data object Gained : FocusEvent

    /** Permanent loss — e.g. another VoIP app forcibly took over. */
    public data object LostPermanent : FocusEvent

    /** Transient loss (call coming in) — pause for the duration. */
    public data object LostTransient : FocusEvent

    /** Transient duck — quieter, not full pause. We treat as LostTransient. */
    public data object LostTransientDuck : FocusEvent
}

/** Owns the daemon's `AudioFocusRequest`. */
public class AudioFocusHandler(
    context: Context,
    audioManager: AudioManager? = null,
) {

    private val audioManager: AudioManager = audioManager
        ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Currently-held [AudioFocusRequest] — `null` if focus released. */
    private var activeRequest: AudioFocusRequest? = null

    /**
     * Cold [Flow] of [FocusEvent]s. The first emission ([FocusEvent.Gained]
     * or [FocusEvent.LostPermanent]) reflects the initial requestAudioFocus
     * result.
     */
    public fun observe(): Flow<FocusEvent> = callbackFlow {
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            val event = when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                -> FocusEvent.Gained
                AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.LostPermanent
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusEvent.LostTransient
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.LostTransientDuck
                else -> return@OnAudioFocusChangeListener
            }
            trySend(event)
        }

        // Always use AudioFocusRequest since minSdk is 33 (O=26).
        val result = requestFocus(listener)

        trySend(
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusEvent.Gained
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusEvent.LostTransient
                else -> FocusEvent.LostPermanent
            },
        )

        awaitClose {
            releaseFocus(listener)
        }
    }

    private fun requestFocus(listener: AudioManager.OnAudioFocusChangeListener): Int {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
        activeRequest = request
        return audioManager.requestAudioFocus(request)
    }

    private fun releaseFocus(listener: AudioManager.OnAudioFocusChangeListener) {
        activeRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        activeRequest = null
    }
}

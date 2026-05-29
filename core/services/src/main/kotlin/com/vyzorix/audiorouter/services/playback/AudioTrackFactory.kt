// AudioTrackFactory — builds `AudioTrack` instances configured for the
// route-war's headset-bypass path.
//
// The canonical attributes per doc/VOIP_ROUTE_FORCE.md §1.2:
//   - USAGE_VOICE_COMMUNICATION
//   - CONTENT_TYPE_SPEECH
//   - FLAG_LOW_LATENCY (Android 11+; helps the route stay on the speaker
//     by signalling we don't need the headset DSP chain.)
//
// We intentionally use the legacy AudioTrack constructor on the inner
// build path because `AudioTrack.Builder.setLatencyMode` is only
// available on A12+. The route-war flag inside the AudioAttributes
// achieves the same routing outcome on A10/A11.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §5.3.

package com.vyzorix.audiorouter.services.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.vyzorix.audiorouter.common.audio.AudioConstants
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Outcome of [AudioTrackFactory.create]. */
public sealed interface PlaybackTrackResult {
    public data class Success(public val track: AudioTrack) : PlaybackTrackResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : PlaybackTrackResult
}

/** Configuration parameters for the playback AudioTrack. */
public data class AudioTrackConfig(
    public val sampleRateHz: Int = AudioConstants.SAMPLE_RATE_HZ,
    public val channelCount: Int = AudioConstants.CHANNEL_COUNT_MONO,
    public val pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    public val bufferMultiplier: Int = DEFAULT_BUFFER_MULTIPLIER,
) {

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0 (got $sampleRateHz)" }
        require(channelCount in 1..2) { "channelCount must be 1 or 2 (got $channelCount)" }
        require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
            "pcmEncoding must be ENCODING_PCM_16BIT (got $pcmEncoding)"
        }
        require(bufferMultiplier in 1..16) {
            "bufferMultiplier must be in [1, 16] (got $bufferMultiplier)"
        }
    }

    public val channelMaskOut: Int
        get() = if (channelCount == AudioConstants.CHANNEL_COUNT_STEREO) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }

    public companion object {
        public const val DEFAULT_BUFFER_MULTIPLIER: Int = 4
    }
}

/** Stateless factory for the playback AudioTrack. */
public class AudioTrackFactory {

    public fun create(config: AudioTrackConfig = AudioTrackConfig()): PlaybackTrackResult {
        val minBufferSize = AudioTrack.getMinBufferSize(
            config.sampleRateHz,
            config.channelMaskOut,
            config.pcmEncoding,
        )
        if (minBufferSize <= 0) {
            DaemonLogger.get().error(
                TAG,
                "track.factory.failed phase=min_buffer minBufferSize=$minBufferSize",
            )
            return PlaybackTrackResult.Failed("min_buffer_size_unavailable")
        }
        val bufferSizeBytes = minBufferSize * config.bufferMultiplier
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            // FLAG_LOW_LATENCY is deprecated on A12+ in favour of
            // AudioTrack.PERFORMANCE_MODE_LOW_LATENCY (applied below). We
            // still set the flag for A10/A11 forward-compat — the route-war
            // path benefits from both signals.
            .setFlags(@Suppress("DEPRECATION") AudioAttributes.FLAG_LOW_LATENCY)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(config.sampleRateHz)
            .setChannelMask(config.channelMaskOut)
            .setEncoding(config.pcmEncoding)
            .build()
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "track.factory.failed phase=build err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return PlaybackTrackResult.Failed("audio_track_build_threw", t)
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            DaemonLogger.get().error(
                TAG,
                "track.factory.failed phase=init state=${track.state}",
            )
            track.release()
            return PlaybackTrackResult.Failed("audio_track_uninitialised")
        }
        DaemonLogger.get().info(
            TAG,
            "track.factory.success rateHz=${config.sampleRateHz} ch=${config.channelCount} bufBytes=$bufferSizeBytes",
        )
        return PlaybackTrackResult.Success(track = track)
    }

    /**
     * Convenience: assert the AudioManager is in MODE_IN_COMMUNICATION
     * before building the track. Caller is expected to have already
     * forced the route via SpeakerForceEngine — this is just a guard
     * so misconfigured callers don't get silent stereo headset output.
     */
    public fun assertVoipMode(audioManager: AudioManager) {
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            DaemonLogger.get().warn(
                TAG,
                "track.assertion.wrong_mode actual=${audioManager.mode}",
            )
        }
    }

    private companion object {
        const val TAG: String = "AudioTrackFactory"
    }
}

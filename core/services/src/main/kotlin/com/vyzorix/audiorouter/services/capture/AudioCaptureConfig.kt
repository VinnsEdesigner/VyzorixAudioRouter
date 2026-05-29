// AudioCaptureConfig — capture-side parameter container.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.3 and
// doc/MEDIA_PROJECTION_FLOW.md §2 (Audio Pipeline table), the capture engine
// must be parameterised over:
//   - Sample rate (48 kHz default; 44.1 kHz fallback when thermal throttling
//     forces a downshift per MEDIA_PROJECTION_FLOW.md §Mitigation 2).
//   - Channel layout (mono S16LE on the Nokia C22 to match the speaker's
//     native channel count and minimize PCM mixer work).
//   - PCM encoding (S16LE is the only universally-supported format on the
//     C22's Unisoc HAL — AudioRecord with FLOAT crashes the HAL with
//     E/audio_hw_primary: invalid format).
//   - Frame size budgets — controls AudioRecord.getMinBufferSize() floor
//     so we don't underflow the HAL and so we don't allocate megabytes
//     of GC-pressure buffers.
//
// Two factory constants:
//   - [DEFAULT]            — 48 kHz mono S16LE, 20 ms frames.
//   - [THERMAL_FALLBACK]   — 44.1 kHz mono S16LE, 20 ms frames.
//
// Layer 5+ thermal monitoring will swap between these via
// [CaptureLifecycleController.reconfigure].

package com.vyzorix.audiorouter.services.capture

import android.media.AudioFormat
import com.vyzorix.audiorouter.common.audio.AudioConstants

/**
 * Pure-data capture configuration. Immutable; build via [DEFAULT] /
 * [THERMAL_FALLBACK] or the [Builder] for one-off variations.
 */
public data class AudioCaptureConfig(
    /** Capture sample rate in Hz. Must be one of 16 000, 22 050, 44 100, 48 000. */
    public val sampleRateHz: Int,
    /** Channel count (1 mono, 2 stereo). */
    public val channelCount: Int,
    /** PCM encoding — only [AudioFormat.ENCODING_PCM_16BIT] is supported on the C22. */
    public val pcmEncoding: Int,
    /** Frame duration in milliseconds. Controls the per-read budget. */
    public val frameDurationMs: Int,
    /** Multiplier applied to the AudioRecord min buffer size to compute the capture buffer. */
    public val readBufferMultiplier: Int,
) {

    init {
        require(sampleRateHz in SUPPORTED_SAMPLE_RATES_HZ) {
            "sampleRateHz must be one of $SUPPORTED_SAMPLE_RATES_HZ (got $sampleRateHz)"
        }
        require(channelCount == AudioConstants.CHANNEL_COUNT_MONO ||
            channelCount == AudioConstants.CHANNEL_COUNT_STEREO) {
            "channelCount must be 1 or 2 (got $channelCount)"
        }
        require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
            "pcmEncoding must be ENCODING_PCM_16BIT (got $pcmEncoding)"
        }
        require(frameDurationMs in 5..40) {
            "frameDurationMs must be in [5, 40] (got $frameDurationMs)"
        }
        require(readBufferMultiplier in 1..16) {
            "readBufferMultiplier must be in [1, 16] (got $readBufferMultiplier)"
        }
    }

    /** Android channel-mask constant matching [channelCount]. */
    public val androidChannelMaskIn: Int
        get() = if (channelCount == AudioConstants.CHANNEL_COUNT_STEREO) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

    /** Bytes per PCM sample (always 2 for S16LE). */
    public val bytesPerSample: Int
        get() = AudioConstants.BYTES_PER_SAMPLE_16BIT

    /** Bytes per frame (sample × channels). */
    public val bytesPerFrame: Int
        get() = bytesPerSample * channelCount

    /** PCM bytes-per-second throughput. */
    public val bytesPerSecond: Int
        get() = sampleRateHz * bytesPerFrame

    /**
     * Bytes per scheduled AudioRecord.read() — derived from [frameDurationMs].
     *
     * 20 ms × 48 kHz × 1 ch × 2 bytes = 1920 bytes per read on the default
     * config.
     */
    public val bytesPerScheduledRead: Int
        get() = (bytesPerSecond * frameDurationMs) / MS_PER_SECOND

    /**
     * Construct a friend [AudioCaptureConfig] with a new sample rate. Used
     * by `CaptureLifecycleController.reconfigure` when thermal events
     * force a downshift.
     */
    public fun withSampleRate(newSampleRateHz: Int): AudioCaptureConfig =
        copy(sampleRateHz = newSampleRateHz)

    /** Builder for one-off configs (mostly tests). */
    public class Builder {
        private var sampleRateHz: Int = AudioConstants.SAMPLE_RATE_HZ
        private var channelCount: Int = AudioConstants.CHANNEL_COUNT_MONO
        private var pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
        private var frameDurationMs: Int = AudioConstants.FRAME_DURATION_MS
        private var readBufferMultiplier: Int = DEFAULT_READ_BUFFER_MULTIPLIER

        public fun sampleRateHz(value: Int): Builder = apply { sampleRateHz = value }
        public fun channelCount(value: Int): Builder = apply { channelCount = value }
        public fun pcmEncoding(value: Int): Builder = apply { pcmEncoding = value }
        public fun frameDurationMs(value: Int): Builder = apply { frameDurationMs = value }
        public fun readBufferMultiplier(value: Int): Builder = apply {
            readBufferMultiplier = value
        }

        public fun build(): AudioCaptureConfig = AudioCaptureConfig(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            pcmEncoding = pcmEncoding,
            frameDurationMs = frameDurationMs,
            readBufferMultiplier = readBufferMultiplier,
        )
    }

    public companion object {
        /** Sample rates the Nokia C22 Unisoc HAL accepts without complaining. */
        public val SUPPORTED_SAMPLE_RATES_HZ: Set<Int> =
            setOf(16_000, 22_050, 44_100, 48_000)

        /** Min read buffer multiplier per AudioRecord docs (×1 is rejected on some HALs). */
        public const val DEFAULT_READ_BUFFER_MULTIPLIER: Int = 4

        private const val MS_PER_SECOND: Int = 1_000

        /** 48 kHz mono S16LE, 20 ms frames — the canonical Layer 4 config. */
        @JvmField
        public val DEFAULT: AudioCaptureConfig = Builder().build()

        /** 44.1 kHz mono S16LE — Mitigation 2 thermal fallback. */
        @JvmField
        public val THERMAL_FALLBACK: AudioCaptureConfig = Builder()
            .sampleRateHz(44_100)
            .build()
    }
}

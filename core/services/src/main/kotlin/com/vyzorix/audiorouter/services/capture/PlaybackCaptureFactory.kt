// PlaybackCaptureFactory — builds the `AudioPlaybackCaptureConfiguration` +
// `AudioRecord` for the system-mix capture path.
//
// Per Android docs:
//   - `AudioPlaybackCaptureConfiguration` is the Android 10+ API that
//     lets you capture other apps' playback streams.
//   - It REQUIRES a `MediaProjection` instance acquired via
//     `MediaProjectionManager.getMediaProjection(...)`.
//   - Audio types that can be captured: MEDIA, GAME, UNKNOWN (i.e.
//     usages that don't carry voice or system protected content).
//
// We don't capture SYSTEM (notifications) or VOICE_COMMUNICATION — voice
// is the route we're FORCING, not the route we want to listen to.
//
// Why a factory class (rather than inlining): tests need to inject a
// fake AudioRecord, and the consumer-facing API
// (`PlaybackCaptureEngine`) shouldn't know which API levels are at
// play — A14 added builder constraints on top of the A10 baseline.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.5.

package com.vyzorix.audiorouter.services.capture

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.annotation.RequiresPermission
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Outcome envelope for [PlaybackCaptureFactory.create]. */
public sealed interface CaptureBuildResult {
    public data class Success(public val record: AudioRecord) : CaptureBuildResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : CaptureBuildResult
}

/**
 * Builds `AudioRecord` instances configured against a live MediaProjection.
 * Stateless; one instance per service is fine.
 */
public class PlaybackCaptureFactory {

    /**
     * Build an `AudioRecord` ready for `startRecording()`. The caller MUST
     * call `release()` on the returned record when finished.
     *
     * The RECORD_AUDIO permission is held implicitly via the host manifest
     * ([app/src/main/AndroidManifest.xml]) — the annotation here exists
     * purely to silence the `MissingPermission` lint check.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public fun create(
        projection: MediaProjection,
        config: AudioCaptureConfig,
    ): CaptureBuildResult {
        val playbackCaptureConfig: AudioPlaybackCaptureConfiguration = try {
            buildPlaybackCaptureConfiguration(projection)
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "capture.factory.failed phase=config err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return CaptureBuildResult.Failed(reason = "config_build_failed", cause = t)
        }

        val audioFormat: AudioFormat = try {
            buildAudioFormat(config)
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "capture.factory.failed phase=format err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return CaptureBuildResult.Failed(reason = "format_build_failed", cause = t)
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            config.androidChannelMaskIn,
            config.pcmEncoding,
        )
        if (minBufferSize <= 0) {
            DaemonLogger.get().error(
                TAG,
                "capture.factory.failed phase=buffer minBufferSize=$minBufferSize rateHz=${config.sampleRateHz}",
            )
            return CaptureBuildResult.Failed(reason = "audio_record_min_buffer_unavailable")
        }
        val bufferSize = minBufferSize * config.readBufferMultiplier

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(playbackCaptureConfig)
                .build()
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "capture.factory.failed phase=record err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return CaptureBuildResult.Failed(reason = "audio_record_build_failed", cause = t)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            DaemonLogger.get().error(
                TAG,
                "capture.factory.failed phase=record_init state=${record.state}",
            )
            record.release()
            return CaptureBuildResult.Failed(reason = "audio_record_uninitialised")
        }
        DaemonLogger.get().info(
            TAG,
            "capture.factory.success rateHz=${config.sampleRateHz} ch=${config.channelCount} bufBytes=$bufferSize",
        )
        return CaptureBuildResult.Success(record = record)
    }

    /**
     * Build the AudioPlaybackCaptureConfiguration for the system mix. We
     * include MEDIA + GAME + UNKNOWN; SYSTEM (notifications) and
     * VOICE_COMMUNICATION are deliberately excluded because they are the
     * route we are forcing, not the route we're listening to.
     */
    private fun buildPlaybackCaptureConfiguration(
        projection: MediaProjection,
    ): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        builder.addMatchingUsage(AudioAttributes.USAGE_GAME)
        builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        return builder.build()
    }

    private fun buildAudioFormat(config: AudioCaptureConfig): AudioFormat =
        AudioFormat.Builder()
            .setEncoding(config.pcmEncoding)
            .setSampleRate(config.sampleRateHz)
            .setChannelMask(config.androidChannelMaskIn)
            .build()

    private companion object {
        const val TAG: String = "PlaybackCaptureFactory"
    }
}

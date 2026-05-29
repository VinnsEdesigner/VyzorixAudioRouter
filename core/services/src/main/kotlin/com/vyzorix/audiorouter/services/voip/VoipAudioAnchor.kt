// VoipAudioAnchor — the silent audio source that holds the OS in VoIP
// dominance.
//
// Per doc/VOIP_ROUTE_FORCE.md §1.1 ("Why we need a silent track"): merely
// setting `MODE_IN_COMMUNICATION` is not enough — Android's
// AudioPolicyManager only treats the daemon as a real VoIP session if it
// is actively producing OR consuming audio with `USAGE_VOICE_COMMUNICATION`
// attributes. If we set the mode but never push frames, AudioPolicyManager
// can downgrade us within ~250ms.
//
// The anchor synthesises a stream of -90 dBFS noise (effectively silent
// but not pure zeros — pure zeros are treated as "stream idle" by the
// policy manager on certain MediaTek HALs and trigger an early downgrade).
// We use the `AudioTrack` low-latency path.
//
// Layer 3 scope: this is a SEPARATE class from the future Layer 4 capture
// pipeline. Capture playback rides this same AudioTrack stack but doesn't
// land until L4.

package com.vyzorix.audiorouter.services.voip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Owns the AudioTrack that streams silent voice-communication-routed frames
 * for the lifetime of the daemon.
 */
public class VoipAudioAnchor(
    /** Output sample rate (canonical: 48 kHz mono S16LE per AUDIO_PIPELINE §2). */
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    /** RNG seed override for deterministic dither — test-only. */
    private val random: Random = Random.Default,
    /** Test-only seam: skip the real AudioTrack and just spin the worker. */
    private val audioTrackFactory: (() -> AudioTrack)? = null,
) {

    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    /** Total frames written since start. */
    @Volatile
    public var framesWritten: Long = 0L
        private set

    /**
     * Start streaming. Idempotent — calling while already running is a no-op.
     * The worker thread is set to high audio priority via [Thread.setPriority];
     * SCHED_FIFO elevation (NOKIA_C22_NOTES §2) is handled by Layer 2's
     * native thread priority guard once the L4 capture pipe is wired.
     */
    public fun start() {
        if (!running.compareAndSet(false, true)) return
        val track = audioTrackFactory?.invoke() ?: defaultAudioTrack()
        audioTrack = track
        track.play()
        workerThread = Thread(::run, "vyzorix-voip-anchor").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /** Stop streaming. Idempotent. */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerThread?.interrupt()
        workerThread = null
        audioTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
    }

    /** Worker body — runs until [running] flips to false. */
    private fun run() {
        val frameCount = MIN_BUFFER_FRAMES
        val buffer = ShortArray(frameCount)
        while (running.get()) {
            // -90 dBFS dither so the stream is nominally "audio" without
            // being audible. Pure zeros tripped the MediaTek
            // downgrade-on-idle bug noted in VOIP_ROUTE_FORCE.md §1.1.
            for (i in buffer.indices) {
                buffer[i] = random.nextInt(from = -32, until = 32).toShort()
            }
            val track = audioTrack ?: break
            val written = try {
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                // Track was released racy; loop exit will follow.
                break
            }
            if (written > 0) {
                framesWritten += written
            } else if (written == AudioTrack.ERROR_INVALID_OPERATION ||
                written == AudioTrack.ERROR_DEAD_OBJECT
            ) {
                // AudioFlinger replaced the track underneath us; bail and let
                // the daemon re-bootstrap on the next focus event.
                break
            }
        }
    }

    private fun defaultAudioTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = if (minBytes <= 0) {
            MIN_BUFFER_FRAMES * 2
        } else {
            minBytes
        }
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
    }

    /** Indicates whether the worker is currently running (test/inspection seam). */
    public val isRunning: Boolean get() = running.get()

    /** Indicates whether an AudioManager.MODE_IN_COMMUNICATION-routed track is alive. */
    public fun anchorAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    public companion object {
        /** 48 kHz S16LE mono — see doc/AUDIO_PIPELINE.md §2 ("canonical PCM format"). */
        public const val DEFAULT_SAMPLE_RATE_HZ: Int = 48_000

        /** Roughly 20ms of frames at 48kHz — short enough to mask jitter, long enough to amortise binder overhead. */
        public const val MIN_BUFFER_FRAMES: Int = 960

        /** Routing audio attributes used by the anchor. */
        public val AUDIO_ATTRIBUTES_USAGE: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION

        /** Stream used pre-API-21 callers (legacy AudioManager.STREAM_VOICE_CALL). */
        public val LEGACY_STREAM: Int = AudioManager.STREAM_VOICE_CALL
    }
}

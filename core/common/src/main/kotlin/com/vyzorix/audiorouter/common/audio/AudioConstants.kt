package com.vyzorix.audiorouter.common.audio

/**
 * Pure-Kotlin PCM / audio engine constants.
 *
 * The numbers here are the canonical Layer 0 contract for sample rate, channel
 * count, and frame size; the native audio engine (Layer 2) reads them via JNI
 * and the Kotlin capture controller (Layer 4) uses them when configuring
 * AudioRecord/AudioPlaybackCaptureConfiguration.
 */
public object AudioConstants {
    public const val SAMPLE_RATE_HZ: Int = 48_000
    public const val CHANNEL_COUNT_MONO: Int = 1
    public const val CHANNEL_COUNT_STEREO: Int = 2
    public const val BYTES_PER_SAMPLE_16BIT: Int = 2
    public const val BYTES_PER_SAMPLE_FLOAT: Int = 4

    /** 20 ms at 48 kHz mono / 16-bit ≈ 1920 bytes — one PCM frame. */
    public const val FRAME_DURATION_MS: Int = 20
    public const val FRAMES_PER_SECOND: Int = 1_000 / FRAME_DURATION_MS

    /** 5-second native ring buffer at 48 kHz stereo 16-bit ≈ 960 000 bytes. */
    public const val RING_BUFFER_BYTES: Int =
        SAMPLE_RATE_HZ * CHANNEL_COUNT_STEREO * BYTES_PER_SAMPLE_16BIT * 5

    /** Comfort-noise threshold below which UnderrunGuard kicks in (RMS dBFS). */
    public const val COMFORT_NOISE_DB_FS: Float = -60.0f

    /** Maximum PCM stall before SpeakerForceEngine re-asserts MODE_IN_COMMUNICATION. */
    public const val STALL_WARN_MS: Long = 500L
}

// PlaybackSource — abstraction for a PCM data source consumed by
// [SpeakerPlaybackEngine].
//
// Encapsulates the read side of the playback pipeline. Implementations include:
//   - [NativePlaybackSource]: reads from the native C++ ring buffer via JNI.
//   - [QueuePlaybackSource]: reads from an in-process queue (fallback path).
//
// This abstraction allows [SpeakerPlaybackEngine] to operate in two modes:
//   1. **Native mode**: read from the native DSP pipeline (lowest latency,
//      full processing chain).
//   2. **Java fallback mode**: read from a queue when the native engine is
//      unavailable or crashed.

package com.vyzorix.audiorouter.services.playback

/**
 * Read-only PCM source consumed by [SpeakerPlaybackEngine].
 *
 * Implementations MUST be thread-safe when called from the playback thread.
 * The canonical implementation ([NativePlaybackSource]) is safe for single-
 * consumer use against the lock-free SPSC ring buffer.
 */
public interface PlaybackSource {

    /**
     * Read up to [lengthBytes] bytes into [dst].
     *
     * @param dst destination buffer to fill.
     * @param offsetBytes start offset in [dst]; typically 0.
     * @param lengthBytes maximum bytes to read.
     * @return the number of bytes actually read; may be less than [lengthBytes]
     *         on underrun. A return of 0 means no data is currently available.
     */
    public fun read(dst: ByteArray, offsetBytes: Int, lengthBytes: Int): Int
}

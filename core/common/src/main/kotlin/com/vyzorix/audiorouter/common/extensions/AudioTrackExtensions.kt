package com.vyzorix.audiorouter.common.extensions

import android.media.AudioTrack
import android.util.Log

/**
 * `AudioTrack` helpers shared by the native-bridge wrapper and the
 * underrun guard.
 */

/**
 * Returns `true` when the track's current play state is `PLAYING` AND
 * the track is initialised. Defensive against the gap between
 * `AudioTrack` construction and the first successful `play()` call.
 */
public fun AudioTrack.isPlayingSafely(): Boolean =
    state == AudioTrack.STATE_INITIALIZED && playState == AudioTrack.PLAYSTATE_PLAYING

/**
 * Blocking `write` with a bounded retry on partial writes / transient
 * underruns. Returns the total number of bytes written across all
 * attempts.
 *
 * Why retry: `AudioTrack.write` may return less than `size` when the
 * native ring buffer is briefly full. The audio engine on the Nokia C22
 * does this routinely during route transitions — retrying with a small
 * sleep clears the back-pressure without spilling samples.
 *
 * @return number of bytes written. May be less than `size` if every retry
 *   returns 0 — that's a real underrun and the caller MUST treat it as a
 *   recovery signal (see `doc/DOC_4` underrun handling).
 */
public fun AudioTrack.writeWithRetry(
    buffer: ByteArray,
    offset: Int = 0,
    size: Int = buffer.size - offset,
    maxAttempts: Int = DEFAULT_WRITE_RETRY_ATTEMPTS,
    sleepBetweenAttemptsMs: Long = DEFAULT_WRITE_RETRY_SLEEP_MS,
    logTag: String = "AudioTrack",
): Int {
    var written = 0
    var attempts = 0
    while (written < size && attempts < maxAttempts) {
        val toWrite = size - written
        val result = write(buffer, offset + written, toWrite)
        when {
            result < 0 -> {
                Log.w(logTag, "AudioTrack.write returned error code $result after $written bytes")
                return written
            }
            result == 0 -> {
                attempts++
                if (sleepBetweenAttemptsMs > 0L) {
                    try {
                        Thread.sleep(sleepBetweenAttemptsMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return written
                    }
                }
            }
            else -> {
                written += result
                attempts = 0 // any forward progress resets the retry budget
            }
        }
    }
    return written
}

/** Default retry budget for partial writes. Tuned to ~10 ms of jitter. */
public const val DEFAULT_WRITE_RETRY_ATTEMPTS: Int = 8

/** Default sleep between retry attempts (ms). */
public const val DEFAULT_WRITE_RETRY_SLEEP_MS: Long = 1L

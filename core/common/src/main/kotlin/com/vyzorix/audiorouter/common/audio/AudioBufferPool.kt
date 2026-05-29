package com.vyzorix.audiorouter.common.audio

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable `ByteArray` pool for the audio capture / playback paths.
 *
 * The hot path between `AudioRecord.read(...)` and `AudioTrack.write(...)`
 * allocates a frame buffer at the native PCM rate ([AudioConstants.FRAMES_PER_SECOND]
 * = 50 Hz). Without pooling this is ~3 MB of garbage per minute — enough
 * to trigger GC pauses that show up as audible glitches on the Nokia C22.
 * Pooling cuts the allocation rate to near-zero in steady state.
 *
 * Lifetime contract:
 *   1. Caller [acquire]s a buffer (or gets a freshly-allocated one if the
 *      pool is empty).
 *   2. Caller writes/reads up to `bufferSize` bytes.
 *   3. Caller MUST [release] the buffer once done. Releasing twice is a
 *      no-op (the pool deduplicates by identity).
 *   4. Buffers held past pool reset are GC'd normally — the pool does NOT
 *      hold strong references via `acquire`.
 *
 * Sizing:
 *   - [bufferSize] is fixed at construction so buffers in the pool are
 *     interchangeable.
 *   - [maxRetained] caps the number of buffers we hang on to. Defaults to
 *     `FRAMES_PER_SECOND` (≈1 second of capture). Anything beyond that is
 *     dropped on release.
 *
 * Threading: the underlying queue is lock-free
 * (`ConcurrentLinkedDeque`); [acquire] / [release] are safe to call from
 * any thread.
 */
public class AudioBufferPool(
    public val bufferSize: Int,
    public val maxRetained: Int = AudioConstants.FRAMES_PER_SECOND,
) {

    init {
        require(bufferSize > 0) { "bufferSize must be > 0 (got $bufferSize)" }
        require(maxRetained > 0) { "maxRetained must be > 0 (got $maxRetained)" }
    }

    private val pool: ConcurrentLinkedDeque<ByteArray> = ConcurrentLinkedDeque()
    private val retained: AtomicInteger = AtomicInteger(0)

    /** Returns a buffer of [bufferSize] bytes. Bytes are NOT zeroed for performance. */
    public fun acquire(): ByteArray {
        val recycled = pool.pollFirst()
        if (recycled != null) {
            retained.decrementAndGet()
            return recycled
        }
        return ByteArray(bufferSize)
    }

    /**
     * Returns [buffer] to the pool. No-op when the pool is already at
     * [maxRetained] (the buffer is dropped for GC).
     *
     * Defensive: rejects buffers of the wrong size. A mismatched-size
     * release indicates a caller bug; we'd rather GC the stray buffer
     * than have the pool start handing out the wrong size.
     */
    public fun release(buffer: ByteArray) {
        if (buffer.size != bufferSize) return
        if (retained.get() >= maxRetained) return
        if (retained.incrementAndGet() > maxRetained) {
            retained.decrementAndGet()
            return
        }
        pool.addFirst(buffer)
    }

    /** Empties the pool. Used by safe-mode reset. */
    public fun reset() {
        pool.clear()
        retained.set(0)
    }

    /** Number of buffers currently held by the pool. Telemetry only. */
    public fun size(): Int = retained.get()
}

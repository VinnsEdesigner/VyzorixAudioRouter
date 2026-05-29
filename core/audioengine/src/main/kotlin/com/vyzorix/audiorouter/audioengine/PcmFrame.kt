// PcmFrame — pooled mono S16LE container shared between the capture thread
// and the JNI ring buffer.
//
// Layer 2 lands the type and the pool; Layer 3 fills in the producer / consumer
// loops. Keeping the type stable now means later layers can refer to the same
// canonical frame without churn.

package com.vyzorix.audiorouter.audioengine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mono 16-bit PCM frame. The backing `data` array is reusable — call
 * [PcmFramePool.release] when done so the GC pressure stays flat in the
 * audio hot path.
 *
 * The pool intentionally returns frames *uninitialised* — the producer must
 * fill every sample it reads, otherwise stale bytes will leak across frames.
 * The audio capture path writes the exact `lengthSamples` it reads, so this
 * is safe in the canonical flow.
 */
public class PcmFrame internal constructor(
    /** Backing buffer; size == capacity, not necessarily current length. */
    public val data: ShortArray,
) {
    /** Number of valid samples currently held by this frame. */
    public var lengthSamples: Int = 0
        internal set

    /** Capacity of the backing buffer in samples. */
    public val capacitySamples: Int
        get() = data.size

    public companion object {
        public const val DEFAULT_CHUNK_SAMPLES: Int = 256
    }
}

/**
 * Lock-free pool of [PcmFrame] instances. Producers borrow via [acquire];
 * consumers return via [release]. Misuse (double-release, foreign frame) is
 * tolerated — the pool simply grows to keep allocation off the audio thread.
 */
public class PcmFramePool(
    private val capacitySamples: Int = PcmFrame.DEFAULT_CHUNK_SAMPLES,
) {
    private val free: ConcurrentLinkedQueue<PcmFrame> = ConcurrentLinkedQueue()

    public fun acquire(): PcmFrame {
        val pooled = free.poll()
        if (pooled != null) {
            pooled.lengthSamples = 0
            return pooled
        }
        return PcmFrame(data = ShortArray(capacitySamples))
    }

    public fun release(frame: PcmFrame) {
        // We don't shrink ShortArrays — Layer 3 picks a canonical chunk size at
        // pipeline init time and the pool stabilises on that capacity.
        if (frame.capacitySamples == capacitySamples) {
            free.offer(frame)
        }
    }

    public fun pooledCount(): Int = free.size
}

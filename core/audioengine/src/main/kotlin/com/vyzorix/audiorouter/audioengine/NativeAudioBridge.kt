// JNI bridge — Kotlin declarations for the C++ entry points in
// `core/audioengine/src/main/cpp/jni_audio_bridge.cpp`.
//
// All methods are `external` (i.e., native) and bind to `libaudioengine.so`.
// The library is loaded lazily through [NativeLoader] — if loading fails the
// engine is unusable; callers should check [NativeAudioBridge.isAvailable]
// before invoking any other method.

package com.vyzorix.audiorouter.audioengine

import androidx.annotation.IntRange

/**
 * Kotlin-side surface of the native audio engine.
 *
 * Layer 2's contract per `doc/BUILD_ORDER.md`:
 *   * Allocate / release a lock-free SPSC ring buffer.
 *   * Write and read PCM samples through it.
 *   * Surface underrun / overrun counters.
 *   * Elevate the calling thread to SCHED_FIFO (with the silent-fallback
 *     read-back check from `doc/NOKIA_C22_NOTES.md` §2.3 already applied
 *     in `thread_priority_guard.cpp`).
 *
 * Layer 3+ will layer `AudioPipelineController.kt` on top of this surface to
 * own the audio capture/playback loop. This file deliberately does NOT do
 * any audio plumbing — it is the JNI seam, nothing more.
 */
public object NativeAudioBridge {

    /** Outcome of the SCHED_FIFO elevation request. */
    public enum class PriorityResult(public val raw: Int) {
        /** SCHED_FIFO confirmed via the post-syscall read-back. */
        RealTime(0),
        /** Engine is running with SCHED_OTHER best-effort priority. */
        BestEffort(1),
        /** `sched_setscheduler` returned non-zero. */
        SyscallFailed(2),
        /**
         * Syscall returned 0 but the read-back showed SCHED_OTHER —
         * the Unisoc SC9863A silent fallback documented in
         * `NOKIA_C22_NOTES.md` §2.2.
         */
        SilentFallback(3);

        public companion object {
            public fun fromRaw(raw: Int): PriorityResult =
                values().firstOrNull { it.raw == raw } ?: BestEffort
        }
    }

    /** Native crash signature most recently observed by `crash_guard.cpp`. */
    public enum class CrashGuardSignal(public val raw: Int) {
        None(0),
        Segv(1),
        Bus(2),
        Fpe(3),
        Illegal(4);

        public companion object {
            public fun fromRaw(raw: Int): CrashGuardSignal =
                values().firstOrNull { it.raw == raw } ?: None
        }
    }

    /** True if `libaudioengine.so` is loaded and ready to receive calls. */
    public val isAvailable: Boolean
        get() = NativeLoader.ensureLoaded()

    /**
     * One-time native initialiser. Idempotent: calling more than once is
     * safe and cheap. Should be called from the foreground service's
     * `onCreate` once Layer 3 lands; for Layer 2 it is called lazily on
     * first use to keep the JNI surface fully self-contained.
     */
    public fun ensureInitialised() {
        if (!NativeLoader.ensureLoaded()) return
        if (initialised) return
        synchronized(this) {
            if (!initialised) {
                nativeInit()
                initialised = true
            }
        }
    }

    /**
     * Allocate a lock-free SPSC ring buffer.
     *
     * @param capacityFrames frame capacity (mono S16LE). MUST be a power of
     *        two; non-power-of-two values are rejected by the native side
     *        and this call will return `0L`.
     * @return opaque native handle, or `0L` on failure.
     */
    public fun allocateRingBuffer(
        @IntRange(from = 1) capacityFrames: Int,
    ): Long {
        ensureInitialised()
        if (!isAvailable) return 0L
        return nativeAllocateRingBuffer(capacityFrames)
    }

    /** Release a ring buffer previously returned by [allocateRingBuffer]. */
    public fun releaseRingBuffer(handle: Long) {
        if (!isAvailable || handle == 0L) return
        nativeReleaseRingBuffer(handle)
    }

    /**
     * Write PCM samples into the ring buffer. Returns the number of bytes
     * actually written; may be less than `lengthBytes` if the buffer is
     * near full (overrun counter is bumped in that case).
     */
    public fun write(handle: Long, src: ByteArray, offsetBytes: Int, lengthBytes: Int): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeRingBufferWrite(handle, src, offsetBytes, lengthBytes)
    }

    /**
     * Read PCM samples from the ring buffer. Returns the number of bytes
     * actually read; short reads bump the underrun counter and the caller
     * may invoke the underrun guard on the remainder.
     */
    public fun read(handle: Long, dst: ByteArray, offsetBytes: Int, lengthBytes: Int): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeRingBufferRead(handle, dst, offsetBytes, lengthBytes)
    }

    /** Frames currently buffered (== available to read). */
    public fun availableRead(handle: Long): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeRingBufferAvailableRead(handle)
    }

    /** Free frames in the buffer (== available to write). */
    public fun availableWrite(handle: Long): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeRingBufferAvailableWrite(handle)
    }

    /** Underrun counter (incremented on every short read). */
    public fun underrunCount(handle: Long): Long {
        if (!isAvailable || handle == 0L) return 0L
        return nativeRingBufferUnderrunCount(handle)
    }

    /** Overrun counter (incremented on every short write). */
    public fun overrunCount(handle: Long): Long {
        if (!isAvailable || handle == 0L) return 0L
        return nativeRingBufferOverrunCount(handle)
    }

    /**
     * Elevate the *calling* thread to SCHED_FIFO at [priority]. Performs
     * the read-back check required for the Nokia C22 Unisoc fallback case;
     * returns [PriorityResult.SilentFallback] when the syscall returns 0
     * but the actual policy is still SCHED_OTHER.
     */
    public fun elevatePriority(priority: Int): PriorityResult {
        if (!isAvailable) return PriorityResult.BestEffort
        return PriorityResult.fromRaw(nativeElevatePriority(priority))
    }

    /** Restore the calling thread to SCHED_OTHER. */
    public fun restorePriority(): PriorityResult {
        if (!isAvailable) return PriorityResult.BestEffort
        return PriorityResult.fromRaw(nativeRestorePriority())
    }

    /** Poll for the most recent fatal-signal observation; clears the flag. */
    public fun pollCrashGuard(): CrashGuardSignal {
        if (!isAvailable) return CrashGuardSignal.None
        return CrashGuardSignal.fromRaw(nativeCrashGuardPoll())
    }

    /** Native `clock_gettime(CLOCK_MONOTONIC)` in nanoseconds. */
    public fun monotonicNs(): Long {
        if (!isAvailable) return 0L
        return nativeMonotonicNs()
    }

    /** Live bytes tracked by `memory_guard.cpp`. */
    public fun liveBytes(): Long {
        if (!isAvailable) return 0L
        return nativeLiveBytes()
    }

    /** Peak live bytes observed since the engine was loaded. */
    public fun peakLiveBytes(): Long {
        if (!isAvailable) return 0L
        return nativePeakLiveBytes()
    }

    /** Engine build identifier; useful for forensic logs. */
    public fun engineVersion(): String {
        if (!isAvailable) return UNAVAILABLE_VERSION
        return nativeEngineVersion()
    }

    /** Identifier returned by [engineVersion] when the native library is unavailable. */
    public const val UNAVAILABLE_VERSION: String = "vyzorix-audioengine/unavailable"

    @Volatile
    private var initialised: Boolean = false

    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeAllocateRingBuffer(capacityFrames: Int): Long
    @JvmStatic private external fun nativeReleaseRingBuffer(handle: Long)
    @JvmStatic private external fun nativeRingBufferWrite(handle: Long, src: ByteArray, offsetBytes: Int, lengthBytes: Int): Int
    @JvmStatic private external fun nativeRingBufferRead(handle: Long, dst: ByteArray, offsetBytes: Int, lengthBytes: Int): Int
    @JvmStatic private external fun nativeRingBufferAvailableRead(handle: Long): Int
    @JvmStatic private external fun nativeRingBufferAvailableWrite(handle: Long): Int
    @JvmStatic private external fun nativeRingBufferUnderrunCount(handle: Long): Long
    @JvmStatic private external fun nativeRingBufferOverrunCount(handle: Long): Long
    @JvmStatic private external fun nativeElevatePriority(priority: Int): Int
    @JvmStatic private external fun nativeRestorePriority(): Int
    @JvmStatic private external fun nativeCrashGuardPoll(): Int
    @JvmStatic private external fun nativeMonotonicNs(): Long
    @JvmStatic private external fun nativeLiveBytes(): Long
    @JvmStatic private external fun nativePeakLiveBytes(): Long
    @JvmStatic private external fun nativeEngineVersion(): String

    /** Test-only — resets `initialised` so the loader fallback path can be exercised hermetically. */
    internal fun resetForTests() {
        synchronized(this) { initialised = false }
    }
}

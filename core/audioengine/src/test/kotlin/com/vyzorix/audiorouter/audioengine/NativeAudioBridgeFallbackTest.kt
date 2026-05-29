package com.vyzorix.audiorouter.audioengine

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies that every public method on [NativeAudioBridge] returns the
 * documented "engine unavailable" sentinel under Robolectric (where
 * `libaudioengine.so` is not loadable). This is the contract Layer 6+
 * relies on for the `audio_fallback_bridge` Java-only path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeAudioBridgeFallbackTest {

    @After
    fun resetLoader() {
        NativeLoader.resetForTests()
        NativeAudioBridge.resetForTests()
    }

    @Test
    fun `isAvailable is false when native library cannot load`() {
        assertFalse(NativeAudioBridge.isAvailable)
    }

    @Test
    fun `allocateRingBuffer returns zero handle when unavailable`() {
        assertEquals(0L, NativeAudioBridge.allocateRingBuffer(capacityFrames = 32768))
    }

    @Test
    fun `ring buffer ops are safe no-ops on the zero handle`() {
        // The bridge must not crash when called with a zero handle in the
        // fallback path — Layer 6's `audio_fallback_bridge` will hand a 0L
        // through to AudioPipelineController during the SoftwareLink path.
        val buffer = ByteArray(64)
        assertEquals(0, NativeAudioBridge.write(handle = 0L, src = buffer, offsetBytes = 0, lengthBytes = 64))
        assertEquals(0, NativeAudioBridge.read(handle = 0L, dst = buffer, offsetBytes = 0, lengthBytes = 64))
        assertEquals(0, NativeAudioBridge.availableRead(0L))
        assertEquals(0, NativeAudioBridge.availableWrite(0L))
        assertEquals(0L, NativeAudioBridge.underrunCount(0L))
        assertEquals(0L, NativeAudioBridge.overrunCount(0L))
    }

    @Test
    fun `priority ops surface BestEffort when engine is unavailable`() {
        assertEquals(NativeAudioBridge.PriorityResult.BestEffort, NativeAudioBridge.elevatePriority(priority = 5))
        assertEquals(NativeAudioBridge.PriorityResult.BestEffort, NativeAudioBridge.restorePriority())
    }

    @Test
    fun `pollCrashGuard reports None when engine is unavailable`() {
        assertEquals(NativeAudioBridge.CrashGuardSignal.None, NativeAudioBridge.pollCrashGuard())
    }

    @Test
    fun `monotonicNs and memory counters return zero when unavailable`() {
        assertEquals(0L, NativeAudioBridge.monotonicNs())
        assertEquals(0L, NativeAudioBridge.liveBytes())
        assertEquals(0L, NativeAudioBridge.peakLiveBytes())
    }

    @Test
    fun `engineVersion reports the sentinel string when unavailable`() {
        assertEquals(NativeAudioBridge.UNAVAILABLE_VERSION, NativeAudioBridge.engineVersion())
    }

    @Test
    fun `PriorityResult fromRaw recognises every documented value`() {
        assertEquals(NativeAudioBridge.PriorityResult.RealTime, NativeAudioBridge.PriorityResult.fromRaw(0))
        assertEquals(NativeAudioBridge.PriorityResult.BestEffort, NativeAudioBridge.PriorityResult.fromRaw(1))
        assertEquals(NativeAudioBridge.PriorityResult.SyscallFailed, NativeAudioBridge.PriorityResult.fromRaw(2))
        assertEquals(NativeAudioBridge.PriorityResult.SilentFallback, NativeAudioBridge.PriorityResult.fromRaw(3))
        assertEquals(NativeAudioBridge.PriorityResult.BestEffort, NativeAudioBridge.PriorityResult.fromRaw(99))
    }

    @Test
    fun `CrashGuardSignal fromRaw round-trips every documented value`() {
        assertEquals(NativeAudioBridge.CrashGuardSignal.None, NativeAudioBridge.CrashGuardSignal.fromRaw(0))
        assertEquals(NativeAudioBridge.CrashGuardSignal.Segv, NativeAudioBridge.CrashGuardSignal.fromRaw(1))
        assertEquals(NativeAudioBridge.CrashGuardSignal.Bus, NativeAudioBridge.CrashGuardSignal.fromRaw(2))
        assertEquals(NativeAudioBridge.CrashGuardSignal.Fpe, NativeAudioBridge.CrashGuardSignal.fromRaw(3))
        assertEquals(NativeAudioBridge.CrashGuardSignal.Illegal, NativeAudioBridge.CrashGuardSignal.fromRaw(4))
        assertEquals(NativeAudioBridge.CrashGuardSignal.None, NativeAudioBridge.CrashGuardSignal.fromRaw(99))
    }
}

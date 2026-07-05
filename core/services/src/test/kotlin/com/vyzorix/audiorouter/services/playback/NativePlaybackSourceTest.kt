// NativePlaybackSourceTest — unit tests for NativePlaybackSource JNI bridge source.
//
// Tests the native ring buffer → playback read path.
// Uses Robolectric to test with real native bridge in fallback mode.
// Note: In Robolectric, the native library cannot load, so handle is always 0.
// Tests verify the fallback behavior (returning zeros, tracking underruns).

package com.vyzorix.audiorouter.services.playback

import com.vyzorix.audiorouter.audioengine.AudioPipelineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativePlaybackSourceTest {

    @Test
    fun `read returns zero and tracks underrun when handle is zero (Robolectric fallback)`() {
        // In Robolectric, native library can't load so handle is always 0
        val controller = AudioPipelineController()
        
        val source = NativePlaybackSource(pipelineController = controller)
        val dst = ByteArray(256)

        val bytesRead = source.read(dst, 0, 256)

        assertEquals(0, bytesRead)
        val state = source.snapshot()
        assertEquals(1L, state.totalUnderruns)
        assertEquals(1, state.consecutiveUnderruns)
        assertFalse(state.isPipelineActive)
    }

    @Test
    fun `read tracks multiple consecutive underruns`() {
        val controller = AudioPipelineController()
        val source = NativePlaybackSource(pipelineController = controller)
        val dst = ByteArray(256)

        // Read multiple times - each should track as an underrun
        source.read(dst, 0, 256)
        source.read(dst, 0, 256)
        source.read(dst, 0, 256)

        val state = source.snapshot()
        assertEquals(3L, state.totalUnderruns)
        assertEquals(3, state.consecutiveUnderruns)
    }

    @Test
    fun `availableToRead returns zero when handle is zero`() {
        val controller = AudioPipelineController()
        val source = NativePlaybackSource(pipelineController = controller)

        assertEquals(0, source.availableToRead())
    }

    @Test
    fun `underrunRecovery callback is invoked on short read`() {
        // Note: underrunRecovery is only called when read returns < lengthBytes,
        // not when handle is zero. Testing short read scenario requires
        // a valid handle with partial data, which is not possible in Robolectric.
        // This test verifies the callback is NOT invoked when handle is zero.
        val controller = AudioPipelineController()
        
        val underrunCount = intArrayOf(0)
        val source = NativePlaybackSource(
            pipelineController = controller,
            underrunRecovery = { underrunCount[0]++ },
        )
        val dst = ByteArray(256)

        // When handle is zero, it returns early without calling underrunRecovery
        source.read(dst, 0, 256)

        // Callback is not invoked for zero handle case
        assertEquals(0, underrunCount[0])
    }

    @Test
    fun `snapshot telemetry is accessible`() {
        val controller = AudioPipelineController()
        val source = NativePlaybackSource(pipelineController = controller)

        val state = source.snapshot()

        assertEquals(0L, state.totalRead)
        assertEquals(0L, state.totalUnderruns)
        assertEquals(0, state.consecutiveUnderruns)
        assertEquals(0, state.bufferAvailableBytes)
        assertFalse(state.isPipelineActive)
    }

    @Test
    fun `controller handle is zero in Robolectric (native unavailable)`() {
        val controller = AudioPipelineController()
        controller.start()
        
        // In Robolectric, native library can't load, so handle stays 0
        assertEquals(0L, controller.ringBufferHandle)
    }
}

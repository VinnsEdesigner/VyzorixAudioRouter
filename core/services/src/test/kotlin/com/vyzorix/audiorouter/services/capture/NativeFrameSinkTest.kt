// NativeFrameSinkTest — unit tests for NativeFrameSink JNI bridge sink.
//
// Tests the capture → native ring buffer path.
// Uses Robolectric to test with real native bridge in fallback mode.
// Note: In Robolectric, the native library cannot load, so handle is always 0.
// Tests verify the fallback behavior (dropping frames, tracking counters).

package com.vyzorix.audiorouter.services.capture

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
class NativeFrameSinkTest {

    @Test
    fun `onFrameCaptured drops frames when handle is zero (Robolectric fallback)`() {
        // In Robolectric, native library can't load so handle is always 0
        val controller = AudioPipelineController()
        
        val sink = NativeFrameSink(pipelineController = controller, maxDroppedPerWindow = 10)
        val pcm = ByteArray(256) { it.toByte() }

        sink.onFrameCaptured(pcm = pcm, offsetBytes = 0, lengthBytes = 256, captureEpochMs = 1000L)

        val state = sink.snapshot()
        assertEquals(0L, state.totalWritten)
        assertEquals(1L, state.totalDropped)
        assertEquals(1, state.consecutiveDropped)
        assertFalse(state.isPipelineActive)
    }

    @Test
    fun `onFrameCaptured drops frames and increments counter when handle is zero`() {
        val controller = AudioPipelineController()
        val sink = NativeFrameSink(pipelineController = controller, maxDroppedPerWindow = 5)
        val pcm = ByteArray(256)

        repeat(3) {
            sink.onFrameCaptured(pcm = pcm, offsetBytes = 0, lengthBytes = 256, captureEpochMs = 1000L + it)
        }

        val state = sink.snapshot()
        assertEquals(3L, state.totalDropped)
        assertEquals(3, state.consecutiveDropped)
        assertFalse(state.isPipelineActive)
    }

    @Test
    fun `consecutive drops accumulate correctly`() {
        val controller = AudioPipelineController()
        val sink = NativeFrameSink(pipelineController = controller, maxDroppedPerWindow = 10)
        val pcm = ByteArray(256)

        repeat(5) {
            sink.onFrameCaptured(pcm = pcm, offsetBytes = 0, lengthBytes = 256, captureEpochMs = 1000L)
        }

        val state = sink.snapshot()
        assertEquals(5, state.consecutiveDropped)
        assertEquals(5L, state.totalDropped)
    }

    @Test
    fun `resetConsecutiveDropped clears counter`() {
        val controller = AudioPipelineController()
        val sink = NativeFrameSink(pipelineController = controller, maxDroppedPerWindow = 5)

        repeat(5) {
            sink.onFrameCaptured(ByteArray(256), 0, 256, 1000L)
        }
        assertEquals(5, sink.snapshot().consecutiveDropped)

        sink.resetConsecutiveDropped()
        assertEquals(0, sink.snapshot().consecutiveDropped)
        // totalDropped should still reflect the drops
        assertEquals(5L, sink.snapshot().totalDropped)
    }

    @Test
    fun `snapshot telemetry is accessible`() {
        val controller = AudioPipelineController()
        val sink = NativeFrameSink(pipelineController = controller)
        
        val state = sink.snapshot()
        
        assertEquals(0L, state.totalWritten)
        assertEquals(0L, state.totalDropped)
        assertEquals(0, state.consecutiveDropped)
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

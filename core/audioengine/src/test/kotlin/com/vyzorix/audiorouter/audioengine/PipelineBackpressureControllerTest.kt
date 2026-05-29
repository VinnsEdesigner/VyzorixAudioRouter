package com.vyzorix.audiorouter.audioengine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineBackpressureControllerTest {

    @Test
    fun `does not drop when pressure stays below high-water threshold`() {
        val controller = PipelineBackpressureController()
        assertFalse(controller.shouldDrop(pressureBp = 0))
        assertFalse(controller.shouldDrop(pressureBp = 1000))
        assertFalse(controller.shouldDrop(pressureBp = 7999))
        assertFalse(controller.isDropping())
    }

    @Test
    fun `starts dropping when pressure crosses high-water and stops at low-water`() {
        val controller = PipelineBackpressureController()
        // Cross high-water -> dropping starts.
        assertTrue(controller.shouldDrop(pressureBp = 8500))
        assertTrue(controller.isDropping())
        // Below high-water but above low-water -> still dropping (hysteresis).
        assertTrue(controller.shouldDrop(pressureBp = 7000))
        // Below low-water -> dropping stops.
        assertFalse(controller.shouldDrop(pressureBp = 5500))
        assertFalse(controller.isDropping())
    }

    @Test
    fun `constructor rejects invalid thresholds`() {
        assertFailsWith<IllegalArgumentException> {
            PipelineBackpressureController(highWaterBp = -1, lowWaterBp = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PipelineBackpressureController(highWaterBp = 10_001, lowWaterBp = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PipelineBackpressureController(highWaterBp = 6000, lowWaterBp = 8000)
        }
    }
}

class PipelineStateTrackerTest {

    @Test
    fun `initial state is Idle`() {
        val tracker = PipelineStateTracker()
        assertEquals(PipelineState.Idle, tracker.state.value)
    }

    @Test
    fun `update transitions to the new state`() {
        val tracker = PipelineStateTracker()
        tracker.update(PipelineState.Initializing)
        assertEquals(PipelineState.Initializing, tracker.state.value)
        tracker.update(PipelineState.Streaming)
        assertEquals(PipelineState.Streaming, tracker.state.value)
    }
}

class NativeSafetyControllerTest {

    @Test
    fun `decision is JavaFallback when native is unavailable`() {
        val controller = NativeSafetyController()
        controller.reconsider(AudioEngineHealthState.Unavailable)
        assertEquals(NativeSafetyController.Decision.JavaFallback, controller.decision.value)
    }

    @Test
    fun `decision is JavaFallback when a crash signal was observed`() {
        val controller = NativeSafetyController()
        val crashed = AudioEngineHealthState.Unavailable.copy(
            isNativeAvailable = true,
            lastCrashSignal = NativeAudioBridge.CrashGuardSignal.Segv,
            priority = NativeAudioBridge.PriorityResult.RealTime,
        )
        controller.reconsider(crashed)
        assertEquals(NativeSafetyController.Decision.JavaFallback, controller.decision.value)
    }

    @Test
    fun `decision tracks priority outcome when native is available`() {
        val controller = NativeSafetyController()
        val healthy = AudioEngineHealthState.Unavailable.copy(
            isNativeAvailable = true,
            priority = NativeAudioBridge.PriorityResult.RealTime,
        )
        controller.reconsider(healthy)
        assertEquals(NativeSafetyController.Decision.NativeRealTime, controller.decision.value)

        val degraded = healthy.copy(priority = NativeAudioBridge.PriorityResult.SilentFallback)
        controller.reconsider(degraded)
        assertEquals(NativeSafetyController.Decision.NativeBestEffort, controller.decision.value)
    }
}

class PcmFramePoolTest {

    @Test
    fun `acquire returns a frame with capacity-sized backing array`() {
        val pool = PcmFramePool(capacitySamples = 256)
        val frame = pool.acquire()
        assertEquals(256, frame.capacitySamples)
        assertEquals(0, frame.lengthSamples)
    }

    @Test
    fun `release recycles the frame for the next acquire`() {
        val pool = PcmFramePool(capacitySamples = 128)
        val first = pool.acquire()
        first.lengthSamples = 50
        pool.release(first)
        val second = pool.acquire()
        // Same instance -> the pool recycled it.
        assertTrue(first === second, "Pool should reuse the released frame")
        // Length was reset on acquire.
        assertEquals(0, second.lengthSamples)
    }
}

class AudioPipelineConfigTest {

    @Test
    fun `rejects non-power-of-two ring buffer capacity`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPipelineConfig(ringBufferFrames = 1000)
        }
    }

    @Test
    fun `rejects zero or negative ring buffer capacity`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPipelineConfig(ringBufferFrames = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AudioPipelineConfig(ringBufferFrames = -1024)
        }
    }

    @Test
    fun `accepts canonical power-of-two values`() {
        AudioPipelineConfig(ringBufferFrames = 1024)
        AudioPipelineConfig(ringBufferFrames = 32_768)
        AudioPipelineConfig(ringBufferFrames = 65_536)
    }
}

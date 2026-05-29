package com.vyzorix.audiorouter.services.capture

import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleCaptureControllerTest {

    private class RecordingListener : IdleCaptureListener {
        val pauses: MutableList<String> = mutableListOf()
        val resumes: MutableList<String> = mutableListOf()
        override fun onPause(reason: String) { pauses += reason }
        override fun onResume(reason: String) { resumes += reason }
    }

    @Test
    fun `silence accumulating to the duration threshold triggers exactly one pause`() {
        val now = AtomicLong(100L)
        val listener = RecordingListener()
        val controller = IdleCaptureController(
            listener = listener,
            silenceThresholdRms = 16,
            silenceDurationMs = 1_000L,
            clock = { now.get() },
        )
        val silence = ByteArray(64) // all zeros
        // First silent frame at t=100 — silenceStartedAt latched to 100.
        controller.observeFrame(silence)
        now.set(500L)
        controller.observeFrame(silence)
        assertEquals(0, listener.pauses.size)
        // At t=1_200 the elapsed window since the latched start (100) is 1_100 ≥ 1_000.
        now.set(1_200L)
        controller.observeFrame(silence)
        assertEquals(1, listener.pauses.size)
        assertEquals("silence", listener.pauses.single())

        // Subsequent silent frames must not re-fire the callback.
        now.set(2_000L)
        controller.observeFrame(silence)
        assertEquals(1, listener.pauses.size)
        assertTrue(controller.isPaused)
    }

    @Test
    fun `activity after pause triggers a single resume callback`() {
        val now = AtomicLong(100L)
        val listener = RecordingListener()
        val controller = IdleCaptureController(
            listener = listener,
            silenceThresholdRms = 16,
            silenceDurationMs = 100L,
            clock = { now.get() },
        )
        val silence = ByteArray(64)
        controller.observeFrame(silence)
        now.set(300L)
        controller.observeFrame(silence)
        assertTrue(controller.isPaused)

        val loud = ByteArray(4).apply {
            // S16LE peak >> threshold.
            this[0] = 0x00.toByte(); this[1] = 0x10.toByte()
        }
        controller.observeFrame(loud)
        assertEquals(1, listener.resumes.size)
        assertEquals("activity", listener.resumes.single())
        assertFalse(controller.isPaused)
    }

    @Test
    fun `bind() late-binds the listener`() {
        val now = AtomicLong(100L)
        val controller = IdleCaptureController(
            silenceThresholdRms = 16,
            silenceDurationMs = 100L,
            clock = { now.get() },
        )
        val listener = RecordingListener()
        controller.bind(listener)
        controller.observeFrame(ByteArray(64))
        now.set(300L)
        controller.observeFrame(ByteArray(64))
        assertEquals(1, listener.pauses.size)
    }

    @Test
    fun `pause() forces a pause regardless of activity`() {
        val listener = RecordingListener()
        val controller = IdleCaptureController(
            listener = listener,
            silenceThresholdRms = 16,
            silenceDurationMs = 1_000L,
            clock = { 0L },
        )
        controller.pause(reason = "projection_died")
        assertTrue(controller.isPaused)
        assertEquals("projection_died", listener.pauses.single())
    }

    @Test
    fun `observeFrame computes peak from S16LE little-endian samples`() {
        val listener = RecordingListener()
        val now = AtomicLong(0L)
        val controller = IdleCaptureController(
            listener = listener,
            silenceThresholdRms = 1_000,
            silenceDurationMs = 100L,
            clock = { now.get() },
        )
        // One sample at peak 0x7FFF (32767) — well above threshold.
        val pcm = byteArrayOf(0xFF.toByte(), 0x7F.toByte())
        controller.observeFrame(pcm)
        now.set(500L)
        // Should NOT have paused — peak >> threshold.
        controller.observeFrame(pcm)
        assertEquals(0, listener.pauses.size)
        assertFalse(controller.isPaused)
    }
}

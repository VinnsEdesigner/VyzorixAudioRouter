package com.vyzorix.audiorouter.services.playback

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackGainControllerTest {

    @Test fun `default mode is NORMAL and effectiveGain is the normal gain`() {
        val controller = AudioTrackController()
        val gain = PlaybackGainController(controller = controller)
        val snap = gain.snapshot()
        assertEquals(GainMode.NORMAL, snap.mode)
        assertTrue(snap.effectiveGain > 0.0f && snap.effectiveGain <= 1.0f)
    }

    @Test fun `setMode ATTENUATED lowers effective gain`() {
        val controller = AudioTrackController()
        val gain = PlaybackGainController(controller = controller)
        val normal = gain.effectiveGain()
        gain.setMode(GainMode.ATTENUATED, GainTransitionContext(reason = "thermal_warn", source = "test"))
        val att = gain.effectiveGain()
        assertTrue(att < normal, "ATTENUATED should reduce gain (normal=$normal att=$att)")
    }

    @Test fun `setMode MUTED produces zero gain`() {
        val controller = AudioTrackController()
        val gain = PlaybackGainController(controller = controller)
        gain.setMode(GainMode.MUTED, GainTransitionContext(reason = "stop", source = "test"))
        assertEquals(0.0f, gain.effectiveGain())
    }

    @Test fun `setMode increments transition count`() {
        val controller = AudioTrackController()
        val gain = PlaybackGainController(controller = controller)
        val baseline = gain.snapshot().transitions
        gain.setMode(GainMode.ATTENUATED, GainTransitionContext(reason = "r", source = "s"))
        gain.setMode(GainMode.NORMAL, GainTransitionContext(reason = "r", source = "s"))
        assertEquals(baseline + 2, gain.snapshot().transitions)
    }

    @Test fun `setMode to current mode returns false (no transition)`() {
        val controller = AudioTrackController()
        val gain = PlaybackGainController(controller = controller)
        val firstResult = gain.setMode(GainMode.NORMAL, GainTransitionContext(reason = "x", source = "s"))
        assertEquals(false, firstResult)
    }
}

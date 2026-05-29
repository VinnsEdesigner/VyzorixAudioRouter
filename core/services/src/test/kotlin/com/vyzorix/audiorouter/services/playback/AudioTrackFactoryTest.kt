package com.vyzorix.audiorouter.services.playback

import android.media.AudioFormat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioTrackFactoryTest {

    @Test
    fun `AudioTrackConfig rejects non-positive sample rates`() {
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(sampleRateHz = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(sampleRateHz = -48_000)
        }
    }

    @Test
    fun `AudioTrackConfig rejects out-of-range channelCount`() {
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(channelCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(channelCount = 3)
        }
    }

    @Test
    fun `AudioTrackConfig rejects non-S16LE encodings`() {
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(pcmEncoding = AudioFormat.ENCODING_PCM_FLOAT)
        }
    }

    @Test
    fun `AudioTrackConfig rejects out-of-range bufferMultiplier`() {
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(bufferMultiplier = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AudioTrackConfig(bufferMultiplier = 32)
        }
    }

    @Test
    fun `channelMaskOut maps mono and stereo to the correct Android masks`() {
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, AudioTrackConfig(channelCount = 1).channelMaskOut)
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, AudioTrackConfig(channelCount = 2).channelMaskOut)
    }

    @Test
    fun `create returns Success with INITIALIZED state for the default config`() {
        val factory = AudioTrackFactory()
        val result = factory.create()
        check(result is PlaybackTrackResult.Success) {
            "Expected Success but got $result"
        }
        result.track.release()
    }
}

package com.vyzorix.audiorouter.common.extensions

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioManagerExtensionsTest {

    private val audioManager: AudioManager
        get() {
            val context: Context = ApplicationProvider.getApplicationContext()
            return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }

    @Test
    fun current_mode_name_round_trips_for_all_modes() {
        val am = audioManager
        am.mode = AudioManager.MODE_NORMAL
        assertEquals("NORMAL", am.getCurrentModeName())
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        assertEquals("IN_COMMUNICATION", am.getCurrentModeName())
        am.mode = AudioManager.MODE_RINGTONE
        assertEquals("RINGTONE", am.getCurrentModeName())
    }

    @Test
    fun observers_return_without_throwing_on_empty_robolectric_audio_manager() {
        // Robolectric reports an empty AudioDeviceInfo[] by default. Both
        // helpers must return cleanly (the actual return value depends on
        // the shadow, so we don't assert specifics).
        val am = audioManager
        // Should not throw.
        am.isSpeakerActive()
        am.isHeadsetPlugged()
        assertTrue(true)
    }
}

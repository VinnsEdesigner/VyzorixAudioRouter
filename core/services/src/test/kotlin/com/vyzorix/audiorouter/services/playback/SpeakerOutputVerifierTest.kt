package com.vyzorix.audiorouter.services.playback

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpeakerOutputVerifierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `verify returns one of the three outcomes`() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val verifier = SpeakerOutputVerifier(audioManager = audioManager)
        val outcome = verifier.verify()
        assertNotNull(outcome)
        assertTrue(
            outcome is SpeakerOutputVerification.OnSpeaker ||
                outcome is SpeakerOutputVerification.NotOnSpeaker ||
                outcome is SpeakerOutputVerification.Unavailable,
        )
    }

    @Test fun `snapshot increments verifications counter`() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val verifier = SpeakerOutputVerifier(audioManager = audioManager)
        verifier.verify()
        verifier.verify()
        assertTrue(verifier.snapshot().verifications >= 2L)
    }
}

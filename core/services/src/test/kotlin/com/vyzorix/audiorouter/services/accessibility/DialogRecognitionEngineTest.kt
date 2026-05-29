package com.vyzorix.audiorouter.services.accessibility

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DialogRecognitionEngineTest {

    @Test
    fun `null root returns NotFound without throwing`() {
        val engine = DialogRecognitionEngine()
        val result = engine.locateConfirmButton(root = null)
        assertSame(DialogRecognitionResult.NotFound, result)
    }
}

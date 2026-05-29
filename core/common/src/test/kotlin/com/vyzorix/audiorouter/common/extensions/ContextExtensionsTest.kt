package com.vyzorix.audiorouter.common.extensions

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContextExtensionsTest {

    @Test
    fun safe_get_system_service_returns_audio_manager() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val am: AudioManager? = ctx.safeGetSystemService()
        assertNotNull(am)
    }

    @Test
    fun foreground_service_type_constant_includes_documented_flags() {
        // Constant must include MEDIA_PROJECTION + MICROPHONE per
        // `doc/SYSTEM_MAP.md` foreground-service requirements.
        assertTrue(
            FOREGROUND_SERVICE_TYPE_DAEMON and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION ==
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        assertTrue(
            FOREGROUND_SERVICE_TYPE_DAEMON and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE ==
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    @Test
    fun is_on_main_thread_when_called_from_unit_test_main_thread() {
        // Robolectric runs unit tests on the main looper by default.
        assertTrue(isOnMainThread())
    }
}

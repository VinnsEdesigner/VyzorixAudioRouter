package com.vyzorix.audiorouter

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VyzorixAppInitializerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `initialize registers the daemon notification channel`() {
        val application = context.applicationContext as android.app.Application
        VyzorixAppInitializer.initialize(
            application = application,
            bootstrapComponent = ComponentName(context, BootstrapActivity::class.java),
            accessibilityServiceComponent = ComponentName(
                context,
                "com.vyzorix.audiorouter.services.accessibility.RouterAccessibilityService",
            ),
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager.getNotificationChannel(NotificationConstants.CHANNEL_DAEMON))
    }
}

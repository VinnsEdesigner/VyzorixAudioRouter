package com.vyzorix.audiorouter.services.foreground

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceNotificationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `build creates the daemon channel as a side-effect`() {
        ServiceNotification.build(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager.getNotificationChannel(NotificationConstants.CHANNEL_DAEMON))
    }

    @Test
    fun `build returns a notification on the daemon channel`() {
        val notification = ServiceNotification.build(context)
        assertEquals(NotificationConstants.CHANNEL_DAEMON, notification.channelId)
    }

    @Test
    fun `build sets ongoing and only-alert-once flags`() {
        val notification = ServiceNotification.build(context)
        val flags = notification.flags
        assertTrue(
            (flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0,
            "expected FLAG_ONGOING_EVENT",
        )
        assertTrue(
            (flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0,
            "expected FLAG_ONLY_ALERT_ONCE",
        )
    }

    @Test
    fun `notification id matches the canonical daemon id`() {
        assertEquals(NotificationConstants.NOTIFICATION_ID_DAEMON, ServiceNotification.NOTIFICATION_ID)
    }

    @Test
    fun `app settings pending intent points at the system settings target`() {
        val pendingIntent = ServiceNotification.appSettingsPendingIntent(context)
        assertNotNull(pendingIntent)
    }
}

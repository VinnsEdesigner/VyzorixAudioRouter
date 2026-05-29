package com.vyzorix.audiorouter.common.utils

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationChannelManagerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `ensureChannels creates the canonical channel set`() {
        NotificationChannelManager.ensureChannels(context)
        val manager = notificationManager()
        assertNotNull(manager.getNotificationChannel(NotificationConstants.CHANNEL_DAEMON))
        assertNotNull(manager.getNotificationChannel(NotificationConstants.CHANNEL_UPDATE))
        assertNotNull(manager.getNotificationChannel(NotificationConstants.CHANNEL_ALERT))
    }

    @Test
    fun `ensureChannels is idempotent`() {
        NotificationChannelManager.ensureChannels(context)
        NotificationChannelManager.ensureChannels(context)
        val manager = notificationManager()
        // Still exactly the canonical three (Robolectric does not auto-create extras).
        assertEquals(3, manager.notificationChannels.size)
    }

    @Test
    fun `channel importances follow the canonical mapping`() {
        NotificationChannelManager.ensureChannels(context)
        val manager = notificationManager()
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationConstants.CHANNEL_DAEMON).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(NotificationConstants.CHANNEL_UPDATE).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotificationConstants.CHANNEL_ALERT).importance,
        )
    }
}

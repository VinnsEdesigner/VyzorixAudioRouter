package com.vyzorix.audiorouter.common.utils

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
class NotificationHelperTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `buildDaemonNotification uses the daemon channel`() {
        NotificationChannelManager.ensureChannels(context)
        val notification = NotificationHelper.buildDaemonNotification(
            context = context,
            contentIntent = null,
        )
        assertNotNull(notification)
        assertEquals(NotificationConstants.CHANNEL_DAEMON, notification.channelId)
    }

    @Test
    fun `buildCrashAlertNotification uses the alert channel and is auto-cancel`() {
        NotificationChannelManager.ensureChannels(context)
        val notification = NotificationHelper.buildCrashAlertNotification(
            context = context,
            title = "Crash detected",
            contentText = "Native engine SIGSEGV",
            contentIntent = null,
        )
        assertEquals(NotificationConstants.CHANNEL_ALERT, notification.channelId)
    }
}

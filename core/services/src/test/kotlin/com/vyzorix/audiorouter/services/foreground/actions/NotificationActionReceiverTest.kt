package com.vyzorix.audiorouter.services.foreground.actions

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationActionReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `pending intents for the three actions can be built`() {
        val pause = QuickToggleAction.buildPendingIntent(context)
        val restart = RestartPipelineAction.buildPendingIntent(context)
        val stop = EmergencyStopAction.buildPendingIntent(context)
        assertNotNull(pause)
        assertNotNull(restart)
        assertNotNull(stop)
    }

    @Test fun `receiver onReceive does not throw on a missing action`() {
        val receiver = NotificationActionReceiver()
        val intent = Intent(NotificationActionReceiver.ACTION_ROUTE)
        // No EXTRA_ACTION set — receiver must log and bail.
        receiver.onReceive(context, intent)
    }

    @Test fun `receiver onReceive does not throw on an unknown action id`() {
        val receiver = NotificationActionReceiver()
        val intent = Intent(NotificationActionReceiver.ACTION_ROUTE).apply {
            putExtra(NotificationActionReceiver.EXTRA_ACTION, "totally_made_up")
        }
        receiver.onReceive(context, intent)
    }
}

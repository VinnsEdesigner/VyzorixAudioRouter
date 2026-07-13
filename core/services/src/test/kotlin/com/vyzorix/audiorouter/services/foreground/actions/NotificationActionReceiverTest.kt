package com.vyzorix.audiorouter.services.foreground.actions

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationActionReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun resetReceiver() {
        NotificationActionReceiver.resetForTests()
    }

    @Test fun `pending intents for the three actions can be built`() {
        val pause = QuickToggleAction.buildPendingIntent(context)
        val restart = RestartPipelineAction.buildPendingIntent(context)
        val stop = EmergencyStopAction.buildPendingIntent(context)
        assertNotNull(pause)
        assertNotNull(restart)
        assertNotNull(stop)
    }

    @Test fun `receiver rejects a missing action without handling`() {
        val receiver = NotificationActionReceiver()
        val intent = Intent(NotificationActionReceiver.ACTION_ROUTE)

        receiver.onReceive(context, intent)

        assertEquals(1L, NotificationActionReceiver.rejectedBroadcastCount())
        assertEquals(0L, NotificationActionReceiver.totalHandledCount())
    }

    @Test fun `receiver records unknown action ids separately from rejected broadcasts`() {
        val receiver = NotificationActionReceiver()
        val intent = Intent(NotificationActionReceiver.ACTION_ROUTE).apply {
            putExtra(NotificationActionReceiver.EXTRA_ACTION, "totally_made_up")
        }

        receiver.onReceive(context, intent)

        assertEquals(1L, NotificationActionReceiver.unknownActionCount())
        assertEquals("totally_made_up", NotificationActionReceiver.lastReceivedAction())
    }

    @Test fun `dispatch invokes attached handler for known action`() {
        val calls = AtomicInteger(0)
        NotificationActionReceiver.attachHandler(QuickToggleAction.ACTION_ID) { _, _ -> calls.incrementAndGet() }
        val intent = Intent(NotificationActionReceiver.ACTION_ROUTE).apply {
            putExtra(NotificationActionReceiver.EXTRA_ACTION, QuickToggleAction.ACTION_ID)
        }

        val result = NotificationActionReceiver.dispatch(context, intent)

        assertTrue(result is NotificationDispatchResult.Handled)
        assertEquals(1, calls.get())
        assertEquals(1L, NotificationActionReceiver.totalReceivedCount())
    }

    @Test fun `actions build the exact service commands consumed by PersistentAudioService`() {
        val rationaleIntent = Intent(NotificationActionReceiver.ACTION_ROUTE).apply {
            putExtra(NotificationActionReceiver.EXTRA_RATIONALE, "button")
        }

        assertEquals(QuickToggleAction.ACTION_SERVICE_TOGGLE, QuickToggleAction.buildServiceIntent(context, rationaleIntent).action)
        assertEquals(RestartPipelineAction.ACTION_SERVICE_RESTART, RestartPipelineAction.buildServiceIntent(context, rationaleIntent).action)
        assertEquals(EmergencyStopAction.ACTION_SERVICE_EMERGENCY_STOP, EmergencyStopAction.buildServiceIntent(context, rationaleIntent).action)
    }
}

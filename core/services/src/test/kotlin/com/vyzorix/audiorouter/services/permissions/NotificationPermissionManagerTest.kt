package com.vyzorix.audiorouter.services.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPermissionManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `evaluate returns one of the four states`() {
        val mgr = NotificationPermissionManager(context = context)
        val state = mgr.evaluate()
        assertTrue(
            state is NotificationPermissionState.Granted ||
                state is NotificationPermissionState.DeniedRevocable ||
                state is NotificationPermissionState.DeniedPermanent ||
                state is NotificationPermissionState.NotApplicable,
        )
    }

    @Test fun `isGranted is callable`() {
        val mgr = NotificationPermissionManager(context = context)
        mgr.isGranted()
    }

    @Test fun `snapshot records evaluations`() {
        val mgr = NotificationPermissionManager(context = context)
        mgr.evaluate()
        mgr.evaluate()
        val snap = mgr.snapshot()
        assertTrue(snap.evaluations >= 2L)
    }
}

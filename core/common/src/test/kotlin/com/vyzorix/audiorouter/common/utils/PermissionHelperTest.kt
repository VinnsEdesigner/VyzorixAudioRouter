package com.vyzorix.audiorouter.common.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.constants.PermissionConstants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionHelperTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `hasPermission returns false for a non-granted permission`() {
        assertFalse(PermissionHelper.hasPermission(context, PermissionConstants.RECORD_AUDIO))
    }

    @Test
    fun `hasPermission returns true after granting via Robolectric`() {
        shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(PermissionConstants.RECORD_AUDIO)
        assertTrue(PermissionHelper.hasPermission(context, PermissionConstants.RECORD_AUDIO))
        assertTrue(PermissionHelper.canRecordAudio(context))
    }

    @Test
    fun `snapshot reports a map keyed by every canonical permission`() {
        val snapshot = PermissionHelper.snapshot(context)
        val expected = setOf(
            PermissionConstants.RECORD_AUDIO,
            PermissionConstants.POST_NOTIFICATIONS,
            PermissionConstants.FOREGROUND_SERVICE,
            PermissionConstants.FOREGROUND_SERVICE_MEDIA_PROJECTION,
            PermissionConstants.REQUEST_INSTALL_PACKAGES,
            PermissionConstants.SYSTEM_ALERT_WINDOW,
            PermissionConstants.WAKE_LOCK,
            PermissionConstants.INTERNET,
            PermissionConstants.ACCESS_NETWORK_STATE,
            PermissionConstants.RECEIVE_BOOT_COMPLETED,
        )
        assertEquals(expected, snapshot.keys)
        // Every value should be a boolean — we don't care which boolean here.
        snapshot.values.forEach { /* type check is enforced by signature */ }
    }
}

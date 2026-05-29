package com.vyzorix.audiorouter.common.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionIntentHelperTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `app permissions PendingIntent is not null and immutable`() {
        val pending = PermissionIntentHelper.openAppPermissionsPendingIntent(context)
        assertNotNull(pending)
        assert(pending.isImmutable)
    }

    @Test
    fun `accessibility settings PendingIntent is not null and immutable`() {
        val pending = PermissionIntentHelper.openAccessibilitySettingsPendingIntent(context)
        assertNotNull(pending)
        assert(pending.isImmutable)
    }

    @Test
    fun `overlay permission PendingIntent is not null and immutable`() {
        val pending = PermissionIntentHelper.openOverlayPermissionPendingIntent(context)
        assertNotNull(pending)
        assert(pending.isImmutable)
    }
}

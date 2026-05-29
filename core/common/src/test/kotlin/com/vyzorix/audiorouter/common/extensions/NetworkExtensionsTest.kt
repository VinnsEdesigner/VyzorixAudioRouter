package com.vyzorix.audiorouter.common.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkExtensionsTest {

    @Test
    fun connectivity_helpers_do_not_throw_under_robolectric() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // Robolectric returns no active network by default. Helpers must
        // gracefully degrade to "NOT connected / NONE transport" without
        // throwing — we only assert the no-throw contract here.
        ctx.isConnected()
        ctx.isInternetValidated()
        ctx.isMetered()
        ctx.getActiveNetworkType()
    }
}

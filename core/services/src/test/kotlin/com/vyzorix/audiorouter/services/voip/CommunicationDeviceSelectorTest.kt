package com.vyzorix.audiorouter.services.voip

import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/**
 * `:core:services` is `minSdk = 33`, so Robolectric is configured to run at
 * SDK 33+. The pre-API-31 short-circuit code path in [CommunicationDeviceSelector]
 * is still on the production class for source-level honesty (the selector
 * is a small piece of code that should not lie about its API requirements),
 * but unit-testing it on a lower-SDK Robolectric configuration is blocked
 * by the manifest's API-33 accessibility attributes. We exercise the real
 * SDK 33 path against the Robolectric shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommunicationDeviceSelectorTest {

    @Test
    fun `API-33 isBuiltinSpeakerActive reads communicationDevice off AudioManager`() {
        val routeManager = AudioRouteManager(ApplicationProvider.getApplicationContext())
        val selector = CommunicationDeviceSelector(routeManager)
        // Robolectric's AudioManager shadow returns null for communicationDevice
        // until something sets it; the selector should report inactive.
        assertFalse(selector.isBuiltinSpeakerActive())
    }

    @Test
    fun `assertBuiltinSpeaker returns false on a fresh shadow without a builtin-speaker device`() {
        val routeManager = AudioRouteManager(ApplicationProvider.getApplicationContext())
        val selector = CommunicationDeviceSelector(routeManager)
        // Robolectric's AudioManager shadow returns an empty availableCommunicationDevices
        // list, so the selector cannot find a built-in speaker to switch to.
        assertFalse(selector.assertBuiltinSpeaker())
    }
}

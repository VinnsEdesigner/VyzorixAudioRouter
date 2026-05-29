package com.vyzorix.audiorouter.services.oem

import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.common.device.profiles.UnknownDeviceProfile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NokiaC22DeviceProfileTest {

    @Test
    fun `Nokia C22 quirks resolve to aggressive 500ms cadence`() {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        assertEquals(500L, profile.routeAssertCadenceMs)
    }

    @Test
    fun `Unknown profile resolves to conservative 1000ms cadence`() {
        val profile = NokiaC22DeviceProfile.forTesting(UnknownDeviceProfile)
        assertEquals(1_000L, profile.routeAssertCadenceMs)
    }

    @Test
    fun `Nokia C22 mode-switch silence gap honors the profile alsa timing`() {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        assertEquals(NokiaC22Profile.alsaTimingGapMs.toLong(), profile.modeSwitchSilenceGapMs)
    }

    @Test
    fun `Unknown profile reports no silence gap`() {
        val profile = NokiaC22DeviceProfile.forTesting(UnknownDeviceProfile)
        assertEquals(0L, profile.modeSwitchSilenceGapMs)
    }

    @Test
    fun `Nokia C22 mode reconfirm interval is the 10s aggressive value`() {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        assertEquals(10_000L, profile.modeReconfirmIntervalMs)
    }

    @Test
    fun `Unknown profile mode reconfirm interval is the 30s relaxed value`() {
        val profile = NokiaC22DeviceProfile.forTesting(UnknownDeviceProfile)
        assertEquals(30_000L, profile.modeReconfirmIntervalMs)
    }

    @Test
    fun `Nokia C22 flags phantom headset at boot`() {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        assertTrue(profile.hasPhantomHeadsetAtBoot)
    }

    @Test
    fun `Nokia C22 flags Bluetooth SCO as unreliable`() {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        assertTrue(profile.isBluetoothScoUnreliable)
    }

    @Test
    fun `Unknown profile does not claim phantom headset or SCO unreliability`() {
        val profile = NokiaC22DeviceProfile.forTesting(UnknownDeviceProfile)
        assertFalse(profile.hasPhantomHeadsetAtBoot)
        assertFalse(profile.isBluetoothScoUnreliable)
    }

    @Test
    fun `forTesting exposes the underlying raw profile`() {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        assertNotNull(profile.rawProfile)
        assertEquals(NokiaC22Profile.deviceClass, profile.rawProfile.deviceClass)
    }
}

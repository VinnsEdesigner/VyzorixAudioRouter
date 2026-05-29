package com.vyzorix.audiorouter.common.device

import com.vyzorix.audiorouter.common.constants.AppConstants
import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.common.device.profiles.UnknownDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeviceQuirkRegistryTest {

    @Test
    fun returns_nokia_c22_profile_for_canonical_identifiers() {
        val profile = DeviceQuirkRegistry.forDevice("HMD Global", "TA-1502")
        assertSame(NokiaC22Profile, profile)
        assertEquals(AppConstants.DEVICE_CLASS_NOKIA_C22, profile.deviceClass)
    }

    @Test
    fun nokia_c22_match_is_case_insensitive_and_trims_whitespace() {
        val profile = DeviceQuirkRegistry.forDevice("  hmd global  ", "ta-1502-dl")
        assertSame(NokiaC22Profile, profile)
    }

    @Test
    fun returns_unknown_profile_for_other_devices() {
        val pixel = DeviceQuirkRegistry.forDevice("Google", "Pixel 6")
        assertSame(UnknownDeviceProfile, pixel)
        assertEquals(AppConstants.DEVICE_CLASS_UNKNOWN, pixel.deviceClass)
    }

    @Test
    fun returns_unknown_profile_for_empty_strings() {
        assertSame(UnknownDeviceProfile, DeviceQuirkRegistry.forDevice("", ""))
    }

    @Test
    fun nokia_c22_profile_has_expected_quirks() {
        assertEquals(SocFamily.UNISOC_SC9863A, NokiaC22Profile.socFamily)
        assertEquals(SchedulerBehavior.SILENT_FALLBACK, NokiaC22Profile.schedulerBehavior)
        assertEquals(
            KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK,
            NokiaC22Profile.keystoreReliability,
        )
        assertEquals(2, NokiaC22Profile.alsaTimingGapMs)
        assert(AudioModeQuirk.NEEDS_MODE_SWITCH_GAP in NokiaC22Profile.audioModeQuirks)
        assert(AudioModeQuirk.PHANTOM_HEADSET_AT_BOOT in NokiaC22Profile.audioModeQuirks)
    }
}

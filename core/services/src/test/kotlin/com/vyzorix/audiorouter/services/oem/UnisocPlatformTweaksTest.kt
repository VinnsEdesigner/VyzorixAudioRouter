package com.vyzorix.audiorouter.services.oem

import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnisocPlatformTweaksTest {

    private fun nokiaProfile(): NokiaC22DeviceProfile =
        NokiaC22DeviceProfile.forTesting(NokiaC22Profile)

    @Test
    fun `nokia profile reports throttle required (SchedulerBehavior is SILENT_FALLBACK)`() {
        val tweaks = UnisocPlatformTweaks(nokiaProfile())
        assertTrue(
            tweaks.requiresPriorityFallbackThrottle,
            "Nokia C22 profile must mark SCHED_FIFO fallback handling required",
        )
    }

    @Test
    fun `fallbackTickCadenceMs doubles the default cadence on Nokia`() {
        val tweaks = UnisocPlatformTweaks(nokiaProfile())
        val cadence = tweaks.fallbackTickCadenceMs(500L)
        assertEquals(1000L, cadence, "cadence should double on throttled profile")
    }

    @Test
    fun `fallbackTickCadenceMs caps at MAX_FALLBACK_CADENCE_MS`() {
        val tweaks = UnisocPlatformTweaks(nokiaProfile())
        val cadence = tweaks.fallbackTickCadenceMs(5_000L)
        assertEquals(
            UnisocPlatformTweaks.MAX_FALLBACK_CADENCE_MS,
            cadence,
            "throttled cadence should never exceed 2s",
        )
    }

    @Test
    fun `postRouteFlipDelayMs reflects the profile's modeSwitchSilenceGapMs`() {
        val profile = nokiaProfile()
        val tweaks = UnisocPlatformTweaks(profile)
        assertEquals(profile.modeSwitchSilenceGapMs, tweaks.postRouteFlipDelayMs)
    }
}

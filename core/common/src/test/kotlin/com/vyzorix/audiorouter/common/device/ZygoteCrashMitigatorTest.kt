package com.vyzorix.audiorouter.common.device

import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.common.device.profiles.UnknownDeviceProfile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZygoteCrashMitigatorTest {

    @Test
    fun `not safe immediately after construction when profile defers init`() {
        var now = 0L
        val clock = { now }
        val mitigator = ZygoteCrashMitigator(profile = NokiaC22Profile, clock = clock)
        assertFalse(mitigator.isSafeToInitNow())
        assertEquals(NokiaC22Profile.zygoteSafeDelayMs, mitigator.remainingDelayMs())
    }

    @Test
    fun `safe once the deferral window has elapsed`() {
        var now = 0L
        val clock = { now }
        val mitigator = ZygoteCrashMitigator(profile = NokiaC22Profile, clock = clock)
        now += NokiaC22Profile.zygoteSafeDelayMs
        assertTrue(mitigator.isSafeToInitNow())
        assertEquals(0L, mitigator.remainingDelayMs())
    }

    @Test
    fun `unknown device profile has a shorter delay than the Nokia C22`() {
        assert(UnknownDeviceProfile.zygoteSafeDelayMs < NokiaC22Profile.zygoteSafeDelayMs)
    }

    @Test
    fun `remaining is clamped at zero past the deadline`() {
        var now = 0L
        val clock = { now }
        val mitigator = ZygoteCrashMitigator(profile = NokiaC22Profile, clock = clock)
        now += NokiaC22Profile.zygoteSafeDelayMs * 10L
        assertEquals(0L, mitigator.remainingDelayMs())
    }

    @Test
    fun `zero-delay profile reports safe immediately`() {
        val zeroProfile = NokiaC22Profile.copy(zygoteSafeDelayMs = 0L)
        var now = 0L
        val clock = { now }
        val mitigator = ZygoteCrashMitigator(profile = zeroProfile, clock = clock)
        assertTrue(mitigator.isSafeToInitNow())
    }
}

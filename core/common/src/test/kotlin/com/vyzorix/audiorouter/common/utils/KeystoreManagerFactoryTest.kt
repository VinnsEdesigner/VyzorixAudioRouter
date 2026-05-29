package com.vyzorix.audiorouter.common.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.device.AudioModeQuirk
import com.vyzorix.audiorouter.common.device.BackgroundRestrictionLevel
import com.vyzorix.audiorouter.common.device.DeviceQuirkProfile
import com.vyzorix.audiorouter.common.device.KeystoreReliability
import com.vyzorix.audiorouter.common.device.SchedulerBehavior
import com.vyzorix.audiorouter.common.device.SocFamily
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreManagerFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nokia_c22_profile_yields_software_back_end() {
        val nokia = profile(KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK)
        val keystore = KeystoreManagerFactory.create(context, nokia)
        assertFalse(keystore.isHardwareBacked)
    }

    @Test
    fun reliable_profile_falls_back_to_software_when_provider_missing() {
        // Robolectric has no AndroidKeyStore provider, so the factory's
        // defensive probe should demote to SoftwareKeystoreManager and
        // emit a single demotion log line.
        val reliable = profile(KeystoreReliability.RELIABLE)

        val log = StringBuilder()
        val keystore = KeystoreManagerFactory.create(context, reliable) { log.append(it).append('\n') }

        assertFalse(keystore.isHardwareBacked, "factory should have demoted to software back-end")
        assert(log.contains(KeystoreManagerFactory.DEMOTION_LOG_MESSAGE)) {
            "expected demotion log message, got: $log"
        }
    }

    private fun profile(reliability: KeystoreReliability): DeviceQuirkProfile = DeviceQuirkProfile(
        deviceClass = "test",
        socFamily = SocFamily.UNKNOWN,
        schedulerBehavior = SchedulerBehavior.RELIABLE_SCHED_FIFO,
        keystoreReliability = reliability,
        backgroundRestrictionLevel = BackgroundRestrictionLevel.PERMISSIVE,
        thermalZones = emptyList(),
        alsaTimingGapMs = 0,
        audioModeQuirks = emptySet<AudioModeQuirk>(),
    )
}

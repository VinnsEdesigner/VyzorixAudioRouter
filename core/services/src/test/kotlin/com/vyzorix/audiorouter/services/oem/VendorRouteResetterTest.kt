package com.vyzorix.audiorouter.services.oem

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.AudioRouteSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VendorRouteResetterTest {

    private class ModeRecordingRouteManager :
        AudioRouteManager(ApplicationProvider.getApplicationContext()) {

        val modeCallSequence: MutableList<Int> = mutableListOf()
        var engageCalls: Int = 0
            private set
        var finalSnapshot: AudioRouteSnapshot = AudioRouteSnapshot(
            mode = AudioManager.MODE_IN_COMMUNICATION,
            isSpeakerphoneOn = true,
            isBluetoothScoOn = false,
            isWiredHeadsetPresent = false,
            builtInSpeakerPresent = true,
            activeOutputs = emptyList(),
        )

        override fun snapshot(): AudioRouteSnapshot = finalSnapshot

        override fun setMode(targetMode: Int) {
            modeCallSequence += targetMode
        }

        override fun engageVoipSpeakerRoute(modeSwitchGapMs: Long) {
            engageCalls++
        }
    }

    private class NoSleepVendorRouteResetter(
        routeManager: AudioRouteManager,
        profile: NokiaC22DeviceProfile,
    ) : VendorRouteResetter(routeManager, profile) {
        override fun sleep(ms: Long) = Unit
    }

    private fun newResetter(routeManager: AudioRouteManager): VendorRouteResetter =
        NoSleepVendorRouteResetter(
            routeManager = routeManager,
            profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile),
        )

    @Test
    fun `resetRoute cycles modes in the canonical order`() {
        val routeManager = ModeRecordingRouteManager()
        val resetter = newResetter(routeManager)
        resetter.resetRoute()
        assertEquals(
            listOf(AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL),
            routeManager.modeCallSequence,
            "resetRoute should cycle NORMAL → RINGTONE → IN_CALL before re-engaging",
        )
        // engageVoipSpeakerRoute is called as the final step to settle back
        // into IN_COMMUNICATION + speakerphone=true.
        assertTrue(routeManager.engageCalls >= 1, "resetRoute should call engageVoipSpeakerRoute at the end")
    }

    @Test
    fun `ROUTE_RECOVERED when AudioManager state matches target after the cycle`() {
        val routeManager = ModeRecordingRouteManager()
        // Snapshot already matches target → ROUTE_RECOVERED.
        val outcome = newResetter(routeManager).resetRoute()
        assertEquals(VendorRouteResetter.Outcome.ROUTE_RECOVERED, outcome)
    }

    @Test
    fun `ROUTE_STILL_DRIFTING when AudioManager state does not match target after the cycle`() {
        val routeManager = ModeRecordingRouteManager()
        routeManager.finalSnapshot = AudioRouteSnapshot(
            mode = AudioManager.MODE_NORMAL,
            isSpeakerphoneOn = false,
            isBluetoothScoOn = false,
            isWiredHeadsetPresent = false,
            builtInSpeakerPresent = true,
            activeOutputs = emptyList(),
        )
        val outcome = newResetter(routeManager).resetRoute()
        assertEquals(VendorRouteResetter.Outcome.ROUTE_STILL_DRIFTING, outcome)
    }
}

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
class NokiaAudioWorkaroundsTest {

    /**
     * AudioRouteManager subclass whose snapshot can be programmed and whose
     * engage/setMode calls are counted. Captures the silent-drop behaviour
     * the workarounds were built to detect.
     */
    private class ProgrammableRouteManager :
        AudioRouteManager(ApplicationProvider.getApplicationContext()) {

        var snapshotResult: AudioRouteSnapshot = AudioRouteSnapshot(
            mode = AudioManager.MODE_NORMAL,
            isSpeakerphoneOn = false,
            isBluetoothScoOn = false,
            isWiredHeadsetPresent = false,
            builtInSpeakerPresent = true,
            activeOutputs = emptyList(),
        )

        /** Number of engageVoipSpeakerRoute calls observed. */
        var engageCalls: Int = 0
            private set

        /** Number of setMode(MODE_NORMAL) calls observed. */
        var normalModeCalls: Int = 0
            private set

        /**
         * If non-null, the snapshot result switches to this on the N-th call
         * to engageVoipSpeakerRoute (1-indexed).
         */
        var snapshotOnEngageCall: Map<Int, AudioRouteSnapshot> = emptyMap()

        override fun snapshot(): AudioRouteSnapshot = snapshotResult

        override fun engageVoipSpeakerRoute(modeSwitchGapMs: Long) {
            engageCalls++
            snapshotOnEngageCall[engageCalls]?.let { snapshotResult = it }
        }

        override fun setMode(targetMode: Int) {
            if (targetMode == AudioManager.MODE_NORMAL) normalModeCalls++
        }
    }

    /** Subclass that no-ops sleep so tests don't actually wait. */
    private class TestableWorkarounds(
        routeManager: AudioRouteManager,
        profile: NokiaC22DeviceProfile,
        maxRetries: Int,
    ) : NokiaAudioWorkarounds(routeManager, profile, maxRetries) {
        override fun sleep(ms: Long) = Unit
    }

    private fun newWorkarounds(
        routeManager: AudioRouteManager,
        maxRetries: Int = NokiaAudioWorkarounds.DEFAULT_MAX_RETRIES,
    ): NokiaAudioWorkarounds = TestableWorkarounds(
        routeManager = routeManager,
        profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile),
        maxRetries = maxRetries,
    )

    @Test
    fun `direct success returns APPLIED_DIRECTLY after a single engage`() {
        val routeManager = ProgrammableRouteManager()
        // First engage flips the snapshot to the target state.
        routeManager.snapshotOnEngageCall = mapOf(
            1 to AudioRouteSnapshot(
                mode = AudioManager.MODE_IN_COMMUNICATION,
                isSpeakerphoneOn = true,
                isBluetoothScoOn = false,
                isWiredHeadsetPresent = false,
                builtInSpeakerPresent = true,
                activeOutputs = emptyList(),
            ),
        )
        val workarounds = newWorkarounds(routeManager)
        val outcome = workarounds.assertVoipSpeakerRoute()
        assertEquals(OemEnforcementResult.APPLIED_DIRECTLY, outcome)
        assertEquals(1, routeManager.engageCalls)
    }

    @Test
    fun `retry success returns APPLIED_AFTER_RETRY`() {
        val routeManager = ProgrammableRouteManager()
        // First engage is silently dropped; second engage (after retry) lands.
        routeManager.snapshotOnEngageCall = mapOf(
            2 to AudioRouteSnapshot(
                mode = AudioManager.MODE_IN_COMMUNICATION,
                isSpeakerphoneOn = true,
                isBluetoothScoOn = false,
                isWiredHeadsetPresent = false,
                builtInSpeakerPresent = true,
                activeOutputs = emptyList(),
            ),
        )
        val workarounds = newWorkarounds(routeManager)
        val outcome = workarounds.assertVoipSpeakerRoute()
        assertEquals(OemEnforcementResult.APPLIED_AFTER_RETRY, outcome)
        // Retry path cycles MODE_NORMAL → IN_COMMUNICATION.
        assertTrue(routeManager.normalModeCalls >= 1, "retry should call setMode(MODE_NORMAL)")
        assertEquals(2, routeManager.engageCalls)
    }

    @Test
    fun `total failure returns FAILED after max retries`() {
        val routeManager = ProgrammableRouteManager()
        // Snapshot never flips → every retry exhausts.
        val workarounds = newWorkarounds(routeManager, maxRetries = 2)
        val outcome = workarounds.assertVoipSpeakerRoute()
        assertEquals(OemEnforcementResult.FAILED, outcome)
        // 1 direct + 2 retries = 3 engage calls.
        assertEquals(3, routeManager.engageCalls)
    }
}

package com.vyzorix.audiorouter.services.voip

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.AudioRouteSnapshot
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import com.vyzorix.audiorouter.services.oem.UnisocPlatformTweaks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerForceEngineTest {

    /**
     * AudioRouteManager subclass that exposes write counters and an
     * overridable snapshot — we don't mock AudioManager itself because
     * Robolectric supplies a working shadow which the production
     * AudioRouteManager already drives.
     */
    private class CountingRouteManager :
        AudioRouteManager(ApplicationProvider.getApplicationContext()) {

        var setSpeakerphoneCalls: Int = 0
            private set
        var setModeCalls: Int = 0
            private set
        var engageCalls: Int = 0
            private set
        var snapshotResult: AudioRouteSnapshot? = null

        override fun snapshot(): AudioRouteSnapshot {
            return snapshotResult ?: defaultSnapshot()
        }

        override fun setMode(targetMode: Int) {
            setModeCalls++
        }

        override fun setSpeakerphoneOn(on: Boolean, silenceGapMs: Long) {
            setSpeakerphoneCalls++
        }

        override fun engageVoipSpeakerRoute(modeSwitchGapMs: Long) {
            engageCalls++
        }

        private fun defaultSnapshot(): AudioRouteSnapshot = AudioRouteSnapshot(
            mode = AudioManager.MODE_IN_COMMUNICATION,
            isSpeakerphoneOn = true,
            isBluetoothScoOn = false,
            isWiredHeadsetPresent = false,
            builtInSpeakerPresent = true,
            activeOutputs = emptyList(),
        )
    }

    /**
     * Stub that always reports the built-in speaker as active. Without this,
     * SpeakerForceEngine treats the Robolectric default
     * (`communicationDevice == null`) as drift and never records quiet ticks.
     */
    private class StubBuiltinSpeakerSelector(routeManager: AudioRouteManager) :
        CommunicationDeviceSelector(routeManager) {
        override fun isBuiltinSpeakerActive(): Boolean = true
        override fun assertBuiltinSpeaker(): Boolean = true
    }

    /**
     * UnisocPlatformTweaks subclass that disables the SCHED_FIFO fallback
     * throttle so tests can use the profile's nominal cadence without being
     * surprised by the 2x doubling that fires in production whenever the
     * profile reports SILENT_FALLBACK scheduler behaviour.
     */
    private class NoThrottleUnisocTweaks(profile: NokiaC22DeviceProfile) :
        UnisocPlatformTweaks(profile) {
        override fun fallbackTickCadenceMs(defaultCadenceMs: Long): Long = defaultCadenceMs
    }

    private fun newEngine(routeManager: AudioRouteManager, testScope: kotlinx.coroutines.CoroutineScope): SpeakerForceEngine {
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        return SpeakerForceEngine(
            scope = testScope,
            routeManager = routeManager,
            profile = profile,
            communicationDeviceSelector = StubBuiltinSpeakerSelector(routeManager),
            unisocTweaks = NoThrottleUnisocTweaks(profile),
        )
    }

    @Test
    fun `engine performs an immediate reassertion on start before the first tick`() = runTest {
        val routeManager = CountingRouteManager()
        val engine = newEngine(routeManager, this)
        val job = engine.start()
        runCurrent()
        assertTrue(routeManager.engageCalls >= 1, "engage should be called on start")
        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `engine counts a quiet tick when the route is already stable`() = runTest {
        val routeManager = CountingRouteManager()
        val engine = newEngine(routeManager, this)
        val job = engine.start()
        runCurrent()
        val cadence = NokiaC22DeviceProfile.forTesting(NokiaC22Profile).routeAssertCadenceMs
        advanceTimeBy(cadence + 50L)
        runCurrent()
        assertTrue(
            engine.quietTickCount >= 1,
            "expected >=1 quiet tick, got ${engine.quietTickCount}",
        )
        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `engine reasserts when snapshot shows speakerphone flipped off`() = runTest {
        val routeManager = CountingRouteManager()
        routeManager.snapshotResult = AudioRouteSnapshot(
            mode = AudioManager.MODE_IN_COMMUNICATION,
            isSpeakerphoneOn = false,
            isBluetoothScoOn = false,
            isWiredHeadsetPresent = false,
            builtInSpeakerPresent = true,
            activeOutputs = emptyList(),
        )
        val engine = newEngine(routeManager, this)
        val job = engine.start()
        runCurrent()
        val baseline = routeManager.engageCalls
        val cadence = NokiaC22DeviceProfile.forTesting(NokiaC22Profile).routeAssertCadenceMs
        advanceTimeBy(cadence + 50L)
        runCurrent()
        assertTrue(
            routeManager.engageCalls > baseline,
            "engine should reassert when isSpeakerphoneOn==false, " +
                "baseline=$baseline now=${routeManager.engageCalls}",
        )
        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `forceReassertNow triggers an extra reassertion`() = runTest {
        val routeManager = CountingRouteManager()
        val engine = newEngine(routeManager, this)
        val job = engine.start()
        runCurrent()
        val before = engine.reassertionCount
        engine.forceReassertNow()
        runCurrent()
        assertTrue(
            engine.reassertionCount > before,
            "expected forceReassertNow to bump reassertionCount; before=$before, after=${engine.reassertionCount}",
        )
        job.cancel()
        advanceUntilIdle()
    }
}

package com.vyzorix.audiorouter.services.managers

import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import com.vyzorix.audiorouter.services.voip.AudioModeKeeper
import com.vyzorix.audiorouter.services.voip.SpeakerForceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerForceManagerTest {

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun manager(
        engineStartCounter: () -> Job = { Job().also { it.complete() } },
        keeperStartCounter: () -> Job = { Job().also { it.complete() } },
    ): SpeakerForceManager {
        val routeManager = AudioRouteManager(ApplicationProvider.getApplicationContext())
        val profile = NokiaC22DeviceProfile.forTesting(NokiaC22Profile)
        return SpeakerForceManager(
            scope = scope,
            routeManager = routeManager,
            profile = profile,
            engineFactory = { s, r, p ->
                FakeSpeakerForceEngine(s, r, p, onStart = engineStartCounter)
            },
            keeperFactory = { s, r, p ->
                FakeAudioModeKeeper(s, r, p, onStart = keeperStartCounter)
            },
        )
    }

    @Test
    fun `manager begins in IDLE`() {
        assertEquals(SpeakerForceState.IDLE, manager().state.value)
    }

    @Test
    fun `engage transitions IDLE to ENGAGED`() {
        val m = manager()
        m.engage()
        assertEquals(SpeakerForceState.ENGAGED, m.state.value)
    }

    @Test
    fun `engage is idempotent while ENGAGED`() {
        val m = manager()
        m.engage()
        m.engage()
        m.engage()
        assertEquals(SpeakerForceState.ENGAGED, m.state.value)
    }

    @Test
    fun `pauseForFocusLoss transitions to PAUSED_FOR_FOCUS`() {
        val m = manager()
        m.engage()
        m.pauseForFocusLoss()
        assertEquals(SpeakerForceState.PAUSED_FOR_FOCUS, m.state.value)
    }

    @Test
    fun `resume re-arms to ENGAGED`() {
        val m = manager()
        m.engage()
        m.pauseForFocusLoss()
        m.resume()
        assertEquals(SpeakerForceState.ENGAGED, m.state.value)
    }

    @Test
    fun `disengage walks back to IDLE`() {
        val m = manager()
        m.engage()
        m.disengage()
        assertEquals(SpeakerForceState.IDLE, m.state.value)
    }

    @Test
    fun `pauseForFocusLoss while idle is a no-op`() {
        val m = manager()
        m.pauseForFocusLoss()
        assertEquals(SpeakerForceState.IDLE, m.state.value)
    }

    /** Subclass of the engine that never touches AudioManager and just records lifecycle. */
    private class FakeSpeakerForceEngine(
        scope: CoroutineScope,
        routeManager: AudioRouteManager,
        profile: NokiaC22DeviceProfile,
        private val onStart: () -> Job,
    ) : SpeakerForceEngine(scope, routeManager, profile) {
        override fun start(): Job = onStart()
    }

    private class FakeAudioModeKeeper(
        scope: CoroutineScope,
        routeManager: AudioRouteManager,
        profile: NokiaC22DeviceProfile,
        private val onStart: () -> Job,
    ) : AudioModeKeeper(scope, routeManager, profile) {
        override fun start(): Job = onStart()
    }
}

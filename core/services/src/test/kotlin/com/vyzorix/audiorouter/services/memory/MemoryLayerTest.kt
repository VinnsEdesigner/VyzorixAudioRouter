package com.vyzorix.audiorouter.services.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryLayerTest {
    @Test
    fun trimCoordinatorSwitchesToCriticalBudget() {
        var reclaimed = false
        val coordinator = ServiceTrimCoordinator(reducer = EmergencyMemoryReducer { reclaimed = true })
        val profile = MemoryProfile(memoryClassMb = 256, largeMemoryClassMb = 384, lowRam = true, diagnosticQueueLimit = 256)

        val decision = coordinator.onTrimMemory(80, profile)

        assertEquals(LowRamMode.Critical, decision.mode)
        assertEquals(1024 * 1024, decision.budget.nativeBufferBytes)
        assertFalse(decision.budget.nonEssentialObserversEnabled)
        assertTrue(decision.emergencyReducerRan)
        assertTrue(reclaimed)
    }

    @Test
    fun profilerConstrainsLowMemoryDevices() {
        val profile = MemoryClassProfiler().classify(memoryClassMb = 192, largeMemoryClassMb = 256, lowRamDevice = false)

        assertTrue(profile.lowRam)
        assertEquals(256, profile.diagnosticQueueLimit)
    }
}

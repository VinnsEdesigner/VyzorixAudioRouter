package com.vyzorix.audiorouter.services.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceLayerTest {

    @Test
    fun adaptiveSamplingControllerNormalInterval() {
        val controller = AdaptiveSamplingController()
        val interval = controller.interval(cpuLoad = 0.3)
        assertEquals(AdaptiveSamplingController.DEFAULT_NORMAL_INTERVAL, interval)
    }

    @Test
    fun adaptiveSamplingControllerReducesOnHighLoad() {
        val controller = AdaptiveSamplingController()
        val interval = controller.interval(cpuLoad = 0.75)
        assertEquals(AdaptiveSamplingController.DEFAULT_REDUCED_INTERVAL, interval)
    }

    @Test
    fun adaptiveSamplingControllerMinimalOnThermalThrottling() {
        val controller = AdaptiveSamplingController()
        val interval = controller.interval(
            cpuLoad = 0.5,
            memoryPressure = 0.3,
            thermalThrottling = true,
        )
        assertEquals(AdaptiveSamplingController.DEFAULT_MINIMAL_INTERVAL, interval)
    }

    @Test
    fun cpuLoadBalancerBoostAudioOnHighLoad() {
        val balancer = CpuLoadBalancer()
        assertTrue(balancer.shouldBoostAudioPriority(0.75))
        assertFalse(balancer.shouldBoostAudioPriority(0.5))
    }

    @Test
    fun cpuLoadBalancerThrottleDiagnosticsOnHighLoad() {
        val balancer = CpuLoadBalancer()
        assertTrue(balancer.shouldThrottleDiagnostics(0.85))
        assertFalse(balancer.shouldThrottleDiagnostics(0.6))
    }

    @Test
    fun cpuLoadBalancerPlanReturnsCorrectNiceValues() {
        val balancer = CpuLoadBalancer()
        val plan = balancer.plan(cpuLoad = 0.5, memoryPressure = 0.0, audioPipelineActive = true)

        assertEquals(-4, plan.audioPriority)
        assertEquals(0, plan.diagnosticsPriority)
        assertEquals(ObserverMode.NORMAL, plan.observerMode)
    }

    @Test
    fun cpuLoadBalancerPlanCriticalLoad() {
        val balancer = CpuLoadBalancer()
        val plan = balancer.plan(cpuLoad = 0.95, memoryPressure = 0.0, audioPipelineActive = true)

        assertEquals(-16, plan.audioPriority)
        assertEquals(ObserverMode.DISABLED, plan.observerMode)
    }

    @Test
    fun featureLoadSheddingKeepsCoreFeatures() {
        val shedder = FeatureLoadShedding()
        val plan = shedder.computePlan(cpuLoad = 0.95)

        assertTrue(plan.features.contains(Feature.ROUTING))
        assertTrue(plan.features.contains(Feature.CAPTURE))
        assertFalse(plan.features.contains(Feature.DIAGNOSTICS))
    }

    @Test
    fun featureLoadSheddingAllFeaturesNormal() {
        val shedder = FeatureLoadShedding()
        val plan = shedder.computePlan(cpuLoad = 0.3)

        assertEquals(LoadLevel.NORMAL, plan.loadLevel)
        assertEquals(6, plan.features.size)
    }

    @Test
    fun featureLoadSheddingCriticalLoad() {
        val shedder = FeatureLoadShedding()
        val plan = shedder.computePlan(cpuLoad = 0.95)

        assertEquals(LoadLevel.CRITICAL, plan.loadLevel)
        assertEquals(2, plan.features.size)
    }

    @Test
    fun thermalMitigationPolicyModerateThermal() {
        val policy = ThermalMitigationPolicy()
        val action = policy.action(thermalStatus = 2)

        assertEquals(MitigationAction.REDUCE_SAMPLING, action)
    }

    @Test
    fun thermalMitigationPolicySevereThermal() {
        val policy = ThermalMitigationPolicy()
        val action = policy.action(thermalStatus = 3)

        assertEquals(MitigationAction.PAUSE_CAPTURE, action)
    }

    @Test
    fun thermalMitigationPolicyCriticalThermal() {
        val policy = ThermalMitigationPolicy()
        val action = policy.action(thermalStatus = 4)

        assertEquals(MitigationAction.MINIMAL_MODE, action)
    }

    @Test
    fun thermalMitigationPolicyShouldPauseAudio() {
        val policy = ThermalMitigationPolicy()

        assertTrue(policy.shouldPauseAudio(ThermalMitigationPolicy.THERMAL_SEVERE))
        assertFalse(policy.shouldPauseAudio(ThermalMitigationPolicy.THERMAL_MODERATE))
    }

    @Test
    fun thermalMitigationPolicySampleRateReduction() {
        val policy = ThermalMitigationPolicy()

        assertEquals(1.0, policy.sampleRateReduction(ThermalMitigationPolicy.THERMAL_MODERATE))
        assertEquals(0.75, policy.sampleRateReduction(ThermalMitigationPolicy.THERMAL_SEVERE))
        assertEquals(0.5, policy.sampleRateReduction(ThermalMitigationPolicy.THERMAL_CRITICAL))
    }
}

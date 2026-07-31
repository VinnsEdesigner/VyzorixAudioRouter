package com.vyzorix.audiorouter.services.monitoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitoringLayerTest {

    @Test
    fun deviceThermalMonitorRecordsSample() {
        val monitor = DeviceThermalMonitor()
        val level = monitor.record(celsius = 35.0f, status = 0)
        assertEquals(ThermalLevel.NOMINAL, level)
    }

    @Test
    fun deviceThermalMonitorDetectsElevated() {
        val monitor = DeviceThermalMonitor()
        val level = monitor.record(celsius = 45.0f, status = 2)
        assertEquals(ThermalLevel.ELEVATED, level)
    }

    @Test
    fun deviceThermalMonitorDetectsCritical() {
        val monitor = DeviceThermalMonitor()
        val level = monitor.record(celsius = 50.0f, status = 4)
        assertEquals(ThermalLevel.CRITICAL, level)
    }

    @Test
    fun deviceThermalMonitorUpdateFromStatus() {
        val monitor = DeviceThermalMonitor()
        val level = monitor.updateFromStatus(DeviceThermalMonitor.THERMAL_MODERATE)
        assertEquals(ThermalLevel.ELEVATED, level)
    }

    @Test
    fun deviceThermalMonitorIsWarning() {
        val monitor = DeviceThermalMonitor()
        monitor.updateFromStatus(DeviceThermalMonitor.THERMAL_SEVERE)
        assertTrue(monitor.isWarning())
    }

    @Test
    fun deviceThermalMonitorIsCritical() {
        val monitor = DeviceThermalMonitor()
        monitor.updateFromStatus(DeviceThermalMonitor.THERMAL_CRITICAL)
        assertTrue(monitor.isCritical())
    }

    @Test
    fun runtimeMemoryMonitorRecordsSample() {
        val monitor = RuntimeMemoryMonitor()
        val level = monitor.record(
            availableMb = 500L,
            totalMb = 2000L,
            lowMemory = false,
            thresholdMb = 200L,
        )
        assertEquals(MemoryLevel.NORMAL, level)
    }

    @Test
    fun runtimeMemoryMonitorDetectsLowMemory() {
        val monitor = RuntimeMemoryMonitor()
        val level = monitor.record(
            availableMb = 150L,
            totalMb = 2000L,
            lowMemory = false,
            thresholdMb = 200L,
        )
        assertEquals(MemoryLevel.LOW, level)
    }

    @Test
    fun runtimeMemoryMonitorDetectsCritical() {
        val monitor = RuntimeMemoryMonitor()
        val level = monitor.record(
            availableMb = 100L,
            totalMb = 2000L,
            lowMemory = false,
            thresholdMb = 200L,
        )
        assertEquals(MemoryLevel.CRITICAL, level)
    }

    @Test
    fun runtimeMemoryMonitorLowMemoryFlag() {
        val monitor = RuntimeMemoryMonitor()
        val level = monitor.record(
            availableMb = 500L,
            totalMb = 2000L,
            lowMemory = true,
            thresholdMb = 200L,
        )
        assertEquals(MemoryLevel.CRITICAL, level)
    }

    @Test
    fun runtimeMemoryMonitorUtilizationPercent() {
        val monitor = RuntimeMemoryMonitor()
        monitor.record(
            availableMb = 400L,
            totalMb = 1000L,
            lowMemory = false,
            thresholdMb = 100L,
        )
        assertEquals(60, monitor.utilizationPercent())
    }

    @Test
    fun runtimeMemoryMonitorIsLowMemory() {
        val monitor = RuntimeMemoryMonitor()
        monitor.record(
            availableMb = 150L,
            totalMb = 2000L,
            lowMemory = false,
            thresholdMb = 200L,
        )
        assertTrue(monitor.isLowMemory())
    }

    @Test
    fun runtimeMemoryMonitorShouldRecommendGC() {
        val monitor = RuntimeMemoryMonitor()
        monitor.record(
            availableMb = 150L,
            totalMb = 2000L,
            lowMemory = false,
            thresholdMb = 200L,
        )
        assertTrue(monitor.shouldRecommendGC())
    }
}

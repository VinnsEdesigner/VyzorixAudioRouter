package com.vyzorix.audiorouter.services.stability

import com.vyzorix.audiorouter.common.enums.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class StabilityClassifierTest {

    @Test
    fun stableWhenNoCrashesOrWarnings() {
        val classifier = StabilityClassifier(clock = { 0L })

        val risk = classifier.classify(
            crashesLastHour = 0,
            thermalWarning = false,
            memoryCritical = false,
        )

        assertEquals(RiskLevel.STABLE, risk)
    }

    @Test
    fun elevatedOnSingleCrash() {
        val classifier = StabilityClassifier(clock = { 0L })

        val risk = classifier.classify(
            crashesLastHour = 1,
            thermalWarning = false,
            memoryCritical = false,
        )

        assertEquals(RiskLevel.HIGH, risk)
    }

    @Test
    fun criticalOnMultipleCrashes() {
        val classifier = StabilityClassifier(clock = { 0L })

        val risk = classifier.classify(
            crashesLastHour = 3,
            thermalWarning = false,
            memoryCritical = false,
        )

        assertEquals(RiskLevel.CRITICAL, risk)
    }

    @Test
    fun criticalOnMemoryCritical() {
        val classifier = StabilityClassifier(clock = { 0L })

        val risk = classifier.classify(
            crashesLastHour = 0,
            thermalWarning = false,
            memoryCritical = true,
        )

        assertEquals(RiskLevel.CRITICAL, risk)
    }

    @Test
    fun highOnThermalWarning() {
        val classifier = StabilityClassifier(clock = { 0L })

        val risk = classifier.classify(
            crashesLastHour = 0,
            thermalWarning = true,
            memoryCritical = false,
        )

        assertEquals(RiskLevel.HIGH, risk)
    }

    @Test
    fun recordsAndCountsCrashes() {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val classifier = StabilityClassifier(clock = { clock.get() })

        classifier.recordCrash(timestampMs = 0L)
        clock.set(30 * 60 * 1000L) // 30 minutes later
        classifier.recordCrash(timestampMs = clock.get())

        assertEquals(2, classifier.crashesLastHour())
    }

    @Test
    fun prunesOldCrashes() {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val classifier = StabilityClassifier(clock = { clock.get() })

        classifier.recordCrash(timestampMs = 0L)
        clock.set(2 * 60 * 60 * 1000L) // 2 hours later

        assertEquals(0, classifier.crashesLastHour())
    }

    @Test
    fun classifiesFromSignals() {
        val classifier = StabilityClassifier(clock = { 0L })

        val signals = StabilitySignals(
            crashesLastHour = 1,
            thermalWarning = false,
            memoryCritical = false,
        )

        val risk = classifier.classifyFromSignals(signals)
        assertEquals(RiskLevel.HIGH, risk)
    }

    @Test
    fun elevatedOnRecentThermalEvent() {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val classifier = StabilityClassifier(clock = { clock.get() })

        // Record a thermal event
        classifier.recordThermalEvent(timestampMs = 0L)

        // No crashes, no current thermal warning, but recent thermal event
        val risk = classifier.classify(
            crashesLastHour = 0,
            thermalWarning = false,
            memoryCritical = false,
        )

        assertEquals(RiskLevel.ELEVATED, risk)
    }

    @Test
    fun thermalEventExpiresAfterWindow() {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val classifier = StabilityClassifier(clock = { clock.get() })

        // Record a thermal event
        classifier.recordThermalEvent(timestampMs = 0L)

        // Advance past the 30-minute thermal event window
        clock.set(31 * 60 * 1000L)

        // Now should be stable again (no recent thermal event)
        val risk = classifier.classify(
            crashesLastHour = 0,
            thermalWarning = false,
            memoryCritical = false,
        )

        assertEquals(RiskLevel.STABLE, risk)
    }

    @Test
    fun thermalEventTrackingIsIndependentOfCrashes() {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val classifier = StabilityClassifier(clock = { clock.get() })

        // Record only a thermal event, no crashes
        classifier.recordThermalEvent(timestampMs = 0L)

        // Should be elevated due to recent thermal event
        assertEquals(RiskLevel.ELEVATED, classifier.classify(0, false, false))

        // Advance time but keep thermal event
        clock.set(15 * 60 * 1000L) // 15 minutes later

        // Should still be elevated
        assertEquals(RiskLevel.ELEVATED, classifier.classify(0, false, false))
    }
}

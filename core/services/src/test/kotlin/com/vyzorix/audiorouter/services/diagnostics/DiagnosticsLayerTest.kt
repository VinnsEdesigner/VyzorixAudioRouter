package com.vyzorix.audiorouter.services.diagnostics

import com.vyzorix.audiorouter.services.diagnostics.system.WindowTransition
import com.vyzorix.audiorouter.services.diagnostics.system.WindowTransitionTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsLayerTest {
    @Test
    fun collectorRetainsMostRecentEventsAndReportsDrops() {
        val collector = LogStreamCollector(maxEntries = 2, clock = { 42L })

        collector.record("one", "first")
        collector.record("two", "second")
        collector.record("three", "third")

        val snapshot = collector.snapshot()
        assertEquals(listOf("two", "three"), snapshot.events.map { it.type })
        assertEquals(1L, snapshot.droppedCount)
        assertEquals(2, snapshot.capacity)
    }

    @Test
    fun drainRemovesOnlyRequestedBatchSize() {
        val collector = LogStreamCollector(maxEntries = 4)
        repeat(3) { collector.record("event$it", "message$it") }

        assertEquals(listOf("event0", "event1"), collector.drain(maxBatchSize = 2).map { it.type })
        assertEquals(listOf("event2"), collector.snapshot().events.map { it.type })
    }

    @Test
    fun correlatesCrashWithRecentForegroundPackage() {
        val engine = EventCorrelationEngine(windowMs = 500L)
        val events = listOf(
            DiagnosticEvent("app_foreground", "Chrome", epochMs = 1_000L, attributes = mapOf("package" to "com.android.chrome")),
        )
        val traces = listOf(CrashTrace("sig", "stack", epochMs = 1_250L))

        val result = engine.correlate(events, traces)

        assertEquals("com.android.chrome", result.single().packageName)
        assertEquals("app_foreground", result.single().eventType)
    }

    @Test
    fun flashCrashWindowProducesTimelineEvent() {
        val event = WindowTransitionTracker().toEvent(WindowTransition("pkg", 1_000L, 1_250L))

        assertNotNull(event)
        assertTrue(event.type == "window_flash_crash")
        assertEquals("250", event.attributes["durationMs"])
    }
}

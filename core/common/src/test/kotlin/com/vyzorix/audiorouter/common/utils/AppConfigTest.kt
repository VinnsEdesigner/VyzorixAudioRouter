package com.vyzorix.audiorouter.common.utils

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppConfigTest {

    @After
    fun tearDown() {
        AppConfig.resetForTests()
    }

    @Test
    fun `current returns default snapshot on a fresh process`() {
        val snapshot = AppConfig.current()
        assertEquals(AppConfigSnapshot.DEFAULT_AUDIO_RESTART_BUDGET, snapshot.audioRestartBudget)
        assertEquals(AppConfigSnapshot.DEFAULT_ROUTE_ASSERT_INTERVAL_MS, snapshot.routeAssertIntervalMs)
        assertTrue(snapshot.telemetryBufferingEnabled)
        assertFalse(snapshot.allowMeteredUpdates)
        assertEquals(emptyMap(), snapshot.featureFlags)
    }

    @Test
    fun `set replaces the snapshot atomically and returns the previous value`() {
        val before = AppConfig.current()
        val next = before.copy(allowMeteredUpdates = true, audioRestartBudget = 12)
        val previous = AppConfig.set(next)
        assertSame(before, previous)
        assertEquals(next, AppConfig.current())
    }

    @Test
    fun `update applies the transformation atomically`() {
        AppConfig.update { it.copy(featureFlags = it.featureFlags + ("dashboardLink" to true)) }
        val current = AppConfig.current()
        assertTrue(current.flag("dashboardLink"))
        assertFalse(current.flag("missingKey"))
        assertTrue(current.flag("missingKey", default = true))
    }

    @Test
    fun `flag falls back to the supplied default when the key is absent`() {
        val snapshot = AppConfigSnapshot()
        assertFalse(snapshot.flag("nope"))
        assertTrue(snapshot.flag("nope", default = true))
    }
}

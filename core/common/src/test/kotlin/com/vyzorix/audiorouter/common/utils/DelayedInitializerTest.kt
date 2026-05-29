package com.vyzorix.audiorouter.common.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DelayedInitializerTest {

    @Test
    fun `block runs after the requested delay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val initializer = DelayedInitializer(scope)
        var fired = false

        val job = initializer.schedule(delayMillis = 500L) { fired = true }
        scope.advanceTimeBy(499L)
        assertFalse(fired, "Should not have fired before the delay elapsed")
        scope.advanceTimeBy(1L)
        scope.advanceUntilIdle()
        assertTrue(fired, "Should have fired after the delay elapsed")
        assertTrue(job.isCompleted)
    }

    @Test
    fun `cancel before fire-time prevents the block from running`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val initializer = DelayedInitializer(scope)
        var fired = false

        val job = initializer.schedule(delayMillis = 1_000L) { fired = true }
        scope.advanceTimeBy(500L)
        job.cancel()
        scope.advanceUntilIdle()
        assertFalse(fired)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `schedule rejects negative delays`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val initializer = DelayedInitializer(scope)
        assertFailsWith<IllegalArgumentException> {
            initializer.schedule(delayMillis = -1L) { }
        }
    }

    @Test
    fun `zero delay runs the block at the next dispatch tick`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val initializer = DelayedInitializer(scope)
        var fired = 0
        initializer.schedule(delayMillis = 0L) { fired += 1 }
        scope.advanceUntilIdle()
        assertEquals(1, fired)
    }
}

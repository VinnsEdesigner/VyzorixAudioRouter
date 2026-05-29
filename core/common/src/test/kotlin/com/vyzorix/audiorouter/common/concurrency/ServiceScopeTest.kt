package com.vyzorix.audiorouter.common.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceScopeTest {

    private val testDispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun supervisor_job_does_not_cancel_siblings_on_child_failure() = runBlocking {
        val scope = ServiceScope(testDispatchers)

        val survivor = scope.async { 42 }
        scope.async<Unit> { throw RuntimeException("dead") }

        assertEquals(42, survivor.await())
        assertTrue(scope.isActive, "scope must remain active under SupervisorJob")
        scope.cancel()
    }
}

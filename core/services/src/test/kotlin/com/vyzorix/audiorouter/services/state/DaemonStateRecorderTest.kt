package com.vyzorix.audiorouter.services.state

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.data.dao.DaemonStateDao
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import com.vyzorix.audiorouter.data.repository.DaemonStateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonStateRecorderTest {

    private class FakeDaemonStateDao : DaemonStateDao {
        val inserted: MutableList<DaemonStateSnapshot> = mutableListOf()

        override suspend fun insert(entity: DaemonStateSnapshot): Long {
            inserted += entity
            return inserted.size.toLong()
        }

        override suspend fun latest(): DaemonStateSnapshot? = inserted.lastOrNull()
        override fun observeLatest(): Flow<DaemonStateSnapshot?> = flowOf(inserted.lastOrNull())
        override suspend fun since(sinceEpochMs: Long): List<DaemonStateSnapshot> =
            inserted.filter { it.snapshotEpochMs >= sinceEpochMs }
        override suspend fun deleteOlderThan(olderThanEpochMs: Long): Int {
            val before = inserted.size
            inserted.removeAll { it.snapshotEpochMs < olderThanEpochMs }
            return before - inserted.size
        }
        override suspend fun deleteAll(): Int {
            val n = inserted.size
            inserted.clear()
            return n
        }
        override suspend fun count(): Int = inserted.size
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `record persists a snapshot containing the supplied state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val dao = FakeDaemonStateDao()
        val recorder = DaemonStateRecorder(
            scope = scope,
            repository = DaemonStateRepository(dao),
            now = { 1_700_000_000_000L },
            dispatcher = dispatcher,
        )
        recorder.record(
            daemonState = DaemonState.RUNNING,
            routeState = RouteState.SPEAKER_FORCED,
            captureState = CaptureState.ACTIVE,
            safeModeEnabled = true,
        )
        scope.advanceUntilIdle()
        assertEquals(1, dao.inserted.size)
        val snapshot = dao.inserted.single()
        assertEquals(1_700_000_000_000L, snapshot.snapshotEpochMs)
        assertEquals(DaemonState.RUNNING, snapshot.daemonState)
        assertEquals(RouteState.SPEAKER_FORCED, snapshot.routeState)
        assertEquals(CaptureState.ACTIVE, snapshot.captureState)
        assertTrue(snapshot.safeModeEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `record swallows repository failures so the hot path never crashes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val throwingDao = object : DaemonStateDao {
            override suspend fun insert(entity: DaemonStateSnapshot): Long {
                throw RuntimeException("disk full")
            }
            override suspend fun latest(): DaemonStateSnapshot? = null
            override fun observeLatest(): Flow<DaemonStateSnapshot?> = flowOf(null)
            override suspend fun since(sinceEpochMs: Long): List<DaemonStateSnapshot> = emptyList()
            override suspend fun deleteOlderThan(olderThanEpochMs: Long): Int = 0
            override suspend fun deleteAll(): Int = 0
            override suspend fun count(): Int = 0
        }
        val recorder = DaemonStateRecorder(
            scope = scope,
            repository = DaemonStateRepository(throwingDao),
            dispatcher = dispatcher,
        )
        // Must not throw — fire-and-forget is the contract.
        recorder.record(
            daemonState = DaemonState.RECOVERING,
            routeState = RouteState.UNKNOWN,
        )
        scope.advanceUntilIdle()
    }
}

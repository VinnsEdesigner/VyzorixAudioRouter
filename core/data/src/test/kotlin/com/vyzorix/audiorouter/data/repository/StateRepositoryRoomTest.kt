package com.vyzorix.audiorouter.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.common.enums.UpdateState
import com.vyzorix.audiorouter.data.database.AppDatabase
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.CrashEvent
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import com.vyzorix.audiorouter.data.entity.PermissionGrantRecord
import com.vyzorix.audiorouter.data.entity.PermissionOutcome
import com.vyzorix.audiorouter.data.entity.RouteHistoryEntry
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason
import com.vyzorix.audiorouter.data.entity.UpdateRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [StateRepository.aggregate] and [StateRepository.clearAll]
 * against an in-memory Room database. The five underlying repositories
 * are real (not mocked) so this also covers the DAO contracts for the
 * new tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StateRepositoryRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var stateRepository: StateRepository
    private lateinit var crashEventRepository: CrashEventRepository
    private lateinit var daemonStateRepository: DaemonStateRepository
    private lateinit var updateRepository: UpdateRepository
    private lateinit var routeHistoryRepository: RouteHistoryRepository
    private lateinit var permissionGrantRepository: PermissionGrantRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crashEventRepository = CrashEventRepository(db.crashEventDao())
        daemonStateRepository = DaemonStateRepository(db.daemonStateDao())
        updateRepository = UpdateRepository(db.updateStateDao())
        routeHistoryRepository = RouteHistoryRepository(db.routeHistoryDao())
        permissionGrantRepository = PermissionGrantRepository(db.permissionGrantDao())
        stateRepository = StateRepository(
            daemonStateRepository = daemonStateRepository,
            crashEventRepository = crashEventRepository,
            updateRepository = updateRepository,
            routeHistoryRepository = routeHistoryRepository,
            permissionGrantRepository = permissionGrantRepository,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aggregate_returns_empty_snapshot_when_db_is_empty() = runTest {
        val snapshot = stateRepository.aggregate()
        assertNull(snapshot.daemonState)
        assertTrue(snapshot.recentCrashes.isEmpty())
        assertNull(snapshot.latestUpdate)
        assertTrue(snapshot.recentRouteTransitions.isEmpty())
        assertTrue(snapshot.recentPermissionEvents.isEmpty())
    }

    @Test
    fun aggregate_reflects_writes_across_every_table() = runTest {
        daemonStateRepository.record(
            DaemonStateSnapshot(
                snapshotEpochMs = 1_700_000_001_000L,
                daemonState = DaemonState.RUNNING,
                routeState = RouteState.SPEAKER_FORCED,
                captureState = CaptureState.ACTIVE,
                safeModeEnabled = false,
            ),
        )
        crashEventRepository.record(
            CrashEvent(
                epochMs = 1_700_000_002_000L,
                crashType = CrashType.APP_BUG,
                signature = "java.lang.NullPointerException at FooKt.bar",
                stackHead = "stack",
                processUptimeMs = 5_000L,
                consecutiveCrashes = 1,
            ),
        )
        updateRepository.record(
            UpdateRecord(
                checkedAtEpochMs = 1_700_000_003_000L,
                updateState = UpdateState.AVAILABLE,
                availableVersionCode = 8L,
                availableVersionName = "0.8.0",
                downloadedPath = null,
                checksumHex = null,
            ),
        )
        routeHistoryRepository.record(
            RouteHistoryEntry(
                transitionEpochMs = 1_700_000_004_000L,
                fromRoute = AudioRouteKind.WIRED_HEADSET,
                toRoute = AudioRouteKind.SPEAKER,
                reason = RouteTransitionReason.PHANTOM_HEADSET_DETECTED,
                audioDeviceId = null,
                originMarker = "test",
            ),
        )
        permissionGrantRepository.record(
            PermissionGrantRecord(
                recordedAtEpochMs = 1_700_000_005_000L,
                permission = "android.permission.RECORD_AUDIO",
                outcome = PermissionOutcome.GRANTED,
                source = "runtime_dialog",
                automationAttemptId = 17L,
            ),
        )

        val snapshot = stateRepository.aggregate()
        val daemon = assertNotNull(snapshot.daemonState)
        assertEquals(DaemonState.RUNNING, daemon.daemonState)
        assertEquals(1, snapshot.recentCrashes.size)
        assertEquals(CrashType.APP_BUG, snapshot.recentCrashes.first().crashType)
        val update = assertNotNull(snapshot.latestUpdate)
        assertEquals(UpdateState.AVAILABLE, update.updateState)
        assertEquals(1, snapshot.recentRouteTransitions.size)
        assertEquals(AudioRouteKind.SPEAKER, snapshot.recentRouteTransitions.first().toRoute)
        assertEquals(1, snapshot.recentPermissionEvents.size)
        assertEquals(PermissionOutcome.GRANTED, snapshot.recentPermissionEvents.first().outcome)
    }

    @Test
    fun clearAll_wipes_every_table_except_update_history() = runTest {
        daemonStateRepository.record(
            DaemonStateSnapshot(
                snapshotEpochMs = 1_700_000_000_001L,
                daemonState = DaemonState.RUNNING,
                routeState = RouteState.SPEAKER_FORCED,
                captureState = CaptureState.ACTIVE,
                safeModeEnabled = false,
            ),
        )
        crashEventRepository.record(
            CrashEvent(
                epochMs = 1_700_000_000_002L,
                crashType = CrashType.APP_BUG,
                signature = "sig",
                stackHead = "head",
                processUptimeMs = 1L,
                consecutiveCrashes = 1,
            ),
        )
        updateRepository.record(
            UpdateRecord(
                checkedAtEpochMs = 1_700_000_000_003L,
                updateState = UpdateState.AVAILABLE,
                availableVersionCode = null,
                availableVersionName = null,
                downloadedPath = null,
                checksumHex = null,
            ),
        )
        routeHistoryRepository.record(
            RouteHistoryEntry(
                transitionEpochMs = 1_700_000_000_004L,
                fromRoute = AudioRouteKind.UNKNOWN,
                toRoute = AudioRouteKind.SPEAKER,
                reason = RouteTransitionReason.INITIAL_BOOT,
                audioDeviceId = null,
                originMarker = "boot",
            ),
        )
        permissionGrantRepository.record(
            PermissionGrantRecord(
                recordedAtEpochMs = 1_700_000_000_005L,
                permission = "android.permission.RECORD_AUDIO",
                outcome = PermissionOutcome.GRANTED,
                source = "boot",
                automationAttemptId = null,
            ),
        )

        val counts = stateRepository.clearAll()
        assertEquals(1, counts.daemonState)
        assertEquals(1, counts.crashEvents)
        // UpdateRepository deliberately omits clear() per DOC_8 forensic-log requirement.
        assertEquals(0, counts.updates)
        assertEquals(1, counts.routeHistory)
        assertEquals(1, counts.permissionGrants)

        // update_state row survives the wipe.
        assertNotNull(updateRepository.latest())
        assertNull(daemonStateRepository.latest())
    }
}

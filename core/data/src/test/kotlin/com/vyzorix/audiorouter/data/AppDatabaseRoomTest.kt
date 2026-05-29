package com.vyzorix.audiorouter.data

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

/**
 * Room schema + DAO smoke test. Uses an in-memory database (NOT SQLCipher)
 * so we exercise the entity / DAO layer without dragging in SQLCipher's
 * native library — that path is exercised by on-device acceptance tests on
 * Nokia C22 per doc/BUILD_ORDER.md Layer 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppDatabaseRoomTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun daemon_state_round_trip() = runTest {
        val dao = db.daemonStateDao()
        assertEquals(0, dao.count())
        assertNull(dao.latest())

        val rowId = dao.insert(
            DaemonStateSnapshot(
                snapshotEpochMs = 1_700_000_000_000L,
                daemonState = DaemonState.RUNNING,
                routeState = RouteState.SPEAKER_FORCED,
                captureState = CaptureState.ACTIVE,
                safeModeEnabled = false,
            ),
        )

        assertEquals(1, dao.count())
        val latest = assertNotNull(dao.latest())
        assertEquals(rowId, latest.id)
        assertEquals(DaemonState.RUNNING, latest.daemonState)
        assertEquals(RouteState.SPEAKER_FORCED, latest.routeState)
        assertEquals(CaptureState.ACTIVE, latest.captureState)
        assertEquals(false, latest.safeModeEnabled)
    }

    @Test
    fun crash_events_indexed_recent_lookup() = runTest {
        val dao = db.crashEventDao()
        repeat(5) { i ->
            dao.insert(
                CrashEvent(
                    epochMs = 1_700_000_000_000L + i,
                    crashType = if (i % 2 == 0) CrashType.NATIVE_FAILURE else CrashType.APP_BUG,
                    signature = "libfoo.so+0x1234",
                    stackHead = "stack-head-$i",
                    processUptimeMs = 60_000L * i,
                    consecutiveCrashes = i + 1,
                ),
            )
        }

        val recent = dao.recent(limit = 3)
        assertEquals(3, recent.size)
        // Descending by epochMs — latest first.
        assertEquals(1_700_000_000_004L, recent.first().epochMs)
        assertEquals(5, dao.countSince(1_700_000_000_000L))
        assertEquals(3, dao.countOfTypeSince(CrashType.NATIVE_FAILURE, 1_700_000_000_000L))
        assertEquals(2, dao.countOfTypeSince(CrashType.APP_BUG, 1_700_000_000_000L))
    }

    @Test
    fun update_state_round_trip() = runTest {
        val dao = db.updateStateDao()
        val rowId = dao.insert(
            UpdateRecord(
                checkedAtEpochMs = 1_700_000_010_000L,
                updateState = UpdateState.DOWNLOADED,
                availableVersionCode = 7L,
                availableVersionName = "0.7.0",
                downloadedPath = "/data/data/com.vyzorix.audiorouter/files/update.apk",
                checksumHex = "abcd".repeat(16),
            ),
        )
        val latest = assertNotNull(dao.latest())
        assertEquals(rowId, latest.id)
        assertEquals(UpdateState.DOWNLOADED, latest.updateState)
        assertEquals("0.7.0", latest.availableVersionName)

        val byState = dao.recentByState(UpdateState.DOWNLOADED, 10)
        assertEquals(1, byState.size)
        assertEquals(rowId, byState.first().id)
    }

    @Test
    fun route_history_round_trip() = runTest {
        val dao = db.routeHistoryDao()
        val rowId = dao.insert(
            RouteHistoryEntry(
                transitionEpochMs = 1_700_000_020_000L,
                fromRoute = AudioRouteKind.WIRED_HEADSET,
                toRoute = AudioRouteKind.SPEAKER,
                reason = RouteTransitionReason.FORCE_LOOP_REASSERT,
                audioDeviceId = 42,
                originMarker = "SpeakerForceEngine.tick",
            ),
        )
        val recent = dao.recent(limit = 1)
        assertEquals(1, recent.size)
        val row = recent.first()
        assertEquals(rowId, row.id)
        assertEquals(AudioRouteKind.WIRED_HEADSET, row.fromRoute)
        assertEquals(AudioRouteKind.SPEAKER, row.toRoute)
        assertEquals(RouteTransitionReason.FORCE_LOOP_REASSERT, row.reason)
        assertEquals(42, row.audioDeviceId)
        assertEquals("SpeakerForceEngine.tick", row.originMarker)

        assertEquals(
            1,
            dao.countOfReasonSince(RouteTransitionReason.FORCE_LOOP_REASSERT, 1_700_000_000_000L),
        )
        assertEquals(
            0,
            dao.countOfReasonSince(RouteTransitionReason.PHANTOM_HEADSET_DETECTED, 1_700_000_000_000L),
        )
        assertEquals(
            1,
            dao.countTransitionsSince(
                fromRoute = AudioRouteKind.WIRED_HEADSET,
                toRoute = AudioRouteKind.SPEAKER,
                sinceEpochMs = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun permission_grants_typed_outcome_queries() = runTest {
        val dao = db.permissionGrantDao()
        val recordAudio = "android.permission.RECORD_AUDIO"

        listOf(PermissionOutcome.DENIED, PermissionOutcome.GRANTED, PermissionOutcome.REVOKED).forEachIndexed { i, outcome ->
            dao.insert(
                PermissionGrantRecord(
                    recordedAtEpochMs = 1_700_000_030_000L + i,
                    permission = recordAudio,
                    outcome = outcome,
                    source = "runtime_dialog",
                    automationAttemptId = null,
                ),
            )
        }

        val latest = assertNotNull(dao.latestForPermission(recordAudio))
        assertEquals(PermissionOutcome.REVOKED, latest.outcome)
        assertEquals(
            1,
            dao.countOutcomesSince(recordAudio, PermissionOutcome.GRANTED, 1_700_000_000_000L),
        )
        assertEquals(
            1,
            dao.countOutcomesSince(recordAudio, PermissionOutcome.REVOKED, 1_700_000_000_000L),
        )
    }
}

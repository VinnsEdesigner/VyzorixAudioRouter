package com.vyzorix.audiorouter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.data.database.AppDatabase
import com.vyzorix.audiorouter.data.entity.CrashEventEntity
import com.vyzorix.audiorouter.data.entity.DaemonStateEntity
import com.vyzorix.audiorouter.data.entity.UpdateStateEntity
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
            DaemonStateEntity(
                snapshotEpochMs = 1_700_000_000_000L,
                daemonState = "RUNNING",
                routeState = "ROUTING",
                captureState = "CAPTURING",
                safeModeEnabled = false,
            ),
        )

        assertEquals(1, dao.count())
        val latest = assertNotNull(dao.latest())
        assertEquals(rowId, latest.id)
        assertEquals("RUNNING", latest.daemonState)
        assertEquals(false, latest.safeModeEnabled)
    }

    @Test
    fun crash_events_indexed_recent_lookup() = runTest {
        val dao = db.crashEventDao()
        repeat(5) { i ->
            dao.insert(
                CrashEventEntity(
                    epochMs = 1_700_000_000_000L + i,
                    crashType = "NATIVE_SIGSEGV",
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
    }

    @Test
    fun update_state_round_trip() = runTest {
        val dao = db.updateStateDao()
        val rowId = dao.insert(
            UpdateStateEntity(
                checkedAtEpochMs = 1_700_000_010_000L,
                updateState = "READY_TO_INSTALL",
                availableVersionCode = 7L,
                availableVersionName = "0.7.0",
                downloadedPath = "/data/data/com.vyzorix.audiorouter/files/update.apk",
                checksumHex = "abcd".repeat(16),
            ),
        )
        val latest = assertNotNull(dao.latest())
        assertEquals(rowId, latest.id)
        assertEquals("READY_TO_INSTALL", latest.updateState)
        assertEquals("0.7.0", latest.availableVersionName)
    }
}

package com.vyzorix.audiorouter.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SettingsDataStore], [RuntimeFlagsStore], and
 * [ProjectionMetadataStore]. We instantiate each store with a
 * Preferences DataStore that writes to a per-test temp file so the
 * tests stay hermetic and don't depend on a real Android [Context]
 * (`Context.preferencesDataStore` delegate is not unit-test friendly).
 */
class DataStoresTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        tempFolder.create()
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // ---------- SettingsDataStore ----------

    @Test
    fun settings_data_store_defaults_match_documented_constants() = runTest {
        val store = SettingsDataStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("settings_defaults.preferences_pb")
            },
        )
        val snapshot = store.snapshot()
        assertEquals(SettingsDataStore.DEFAULT_FORCE_SPEAKER_ENABLED, snapshot.forceSpeakerEnabled)
        assertEquals(SettingsDataStore.DEFAULT_FORCE_LOOP_INTERVAL_MS, snapshot.forceLoopIntervalMs)
        assertFalse(snapshot.safeModeRequested)
        assertEquals(SettingsDataStore.DEFAULT_CRASH_RETENTION_DAYS, snapshot.crashRetentionDays)
        assertEquals(SettingsDataStore.DEFAULT_LOG_LEVEL, snapshot.logLevel)
    }

    @Test
    fun settings_data_store_round_trips_writes() = runTest {
        val store = SettingsDataStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("settings_round_trip.preferences_pb")
            },
        )
        store.setForceSpeakerEnabled(false)
        store.setForceLoopIntervalMs(250L)
        store.setSafeModeRequested(true)
        store.setCrashRetentionDays(7)
        store.setLogLevel("DEBUG")

        val snapshot = store.snapshot()
        assertFalse(snapshot.forceSpeakerEnabled)
        assertEquals(250L, snapshot.forceLoopIntervalMs)
        assertTrue(snapshot.safeModeRequested)
        assertEquals(7, snapshot.crashRetentionDays)
        assertEquals("DEBUG", snapshot.logLevel)
    }

    @Test
    fun settings_data_store_clear_resets_to_defaults() = runTest {
        val store = SettingsDataStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("settings_clear.preferences_pb")
            },
        )
        store.setForceSpeakerEnabled(false)
        store.setLogLevel("ERROR")
        store.clear()
        val snapshot = store.snapshot()
        assertEquals(SettingsDataStore.DEFAULT_FORCE_SPEAKER_ENABLED, snapshot.forceSpeakerEnabled)
        assertEquals(SettingsDataStore.DEFAULT_LOG_LEVEL, snapshot.logLevel)
    }

    // ---------- RuntimeFlagsStore ----------

    @Test
    fun runtime_flags_store_returns_default_for_unwritten_flag() = runTest {
        val store = RuntimeFlagsStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("runtime_flags_default.preferences_pb")
            },
        )
        for (flag in RuntimeFlagsStore.Flag.values()) {
            assertEquals(flag.default, store.isEnabled(flag), "default for $flag")
        }
    }

    @Test
    fun runtime_flags_store_round_trip_and_snapshot() = runTest {
        val store = RuntimeFlagsStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("runtime_flags_round.preferences_pb")
            },
        )
        store.setEnabled(RuntimeFlagsStore.Flag.AGGRESSIVE_RECOVERY, true)
        store.setEnabled(RuntimeFlagsStore.Flag.VERBOSE_ROUTE_LOGGING, true)

        val snapshot = store.snapshot()
        assertTrue(snapshot.getValue(RuntimeFlagsStore.Flag.AGGRESSIVE_RECOVERY))
        assertTrue(snapshot.getValue(RuntimeFlagsStore.Flag.VERBOSE_ROUTE_LOGGING))
        assertFalse(snapshot.getValue(RuntimeFlagsStore.Flag.ENABLE_DIAGNOSTIC_OVERLAY))
    }

    @Test
    fun runtime_flags_store_tracks_unknown_flags() = runTest {
        val store = RuntimeFlagsStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("runtime_flags_unknown.preferences_pb")
            },
        )
        store.recordUnknownFlag("experimental_foo")
        store.recordUnknownFlag("experimental_bar")
        assertEquals(setOf("experimental_foo", "experimental_bar"), store.unknownFlags.first())
        store.forgetUnknownFlag("experimental_foo")
        assertEquals(setOf("experimental_bar"), store.unknownFlags.first())
    }

    // ---------- ProjectionMetadataStore ----------

    @Test
    fun projection_metadata_store_records_session_lifecycle() = runTest {
        val store = ProjectionMetadataStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("projection_lifecycle.preferences_pb")
            },
        )
        assertNull(store.lastSessionStartEpochMs.first())

        store.recordSessionStart(
            startEpochMs = 1_700_000_000_000L,
            sampleRateHz = 48_000,
            channelCount = 2,
            triggerOrigin = "user_command",
        )
        assertEquals(1_700_000_000_000L, store.lastSessionStartEpochMs.first())
        assertEquals(48_000, store.lastSampleRateHz.first())
        assertEquals(2, store.lastChannelCount.first())
        assertEquals("user_command", store.lastTriggerOrigin.first())
        assertNull(store.lastSessionStopEpochMs.first())

        store.recordSessionStop(1_700_000_005_000L)
        assertEquals(1_700_000_005_000L, store.lastSessionStopEpochMs.first())

        val snapshot = store.snapshot()
        assertNotNull(snapshot.lastSessionStartEpochMs)
        assertNotNull(snapshot.lastSessionStopEpochMs)
    }

    @Test
    fun projection_metadata_store_clears() = runTest {
        val store = ProjectionMetadataStore(
            PreferenceDataStoreFactory.create(scope = testScope) {
                tempFolder.newFile("projection_clear.preferences_pb")
            },
        )
        store.recordSessionStart(1L, 16_000, 1, "test")
        store.clear()
        assertNull(store.lastSessionStartEpochMs.first())
        assertNull(store.lastSampleRateHz.first())
    }
}

/** Convenience extension so tests can read flag values from a snapshot map. */
private fun Map<RuntimeFlagsStore.Flag, Boolean>.getValue(flag: RuntimeFlagsStore.Flag): Boolean =
    this[flag] ?: error("snapshot missing flag $flag")

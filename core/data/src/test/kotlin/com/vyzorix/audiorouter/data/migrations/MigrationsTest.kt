package com.vyzorix.audiorouter.data.migrations

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyPrefsMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME)
    }

    @Test
    fun should_migrate_returns_false_when_legacy_prefs_absent() = runTest {
        val migration = LegacyPrefsMigration.build(context)
        assertFalse(migration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun migrate_hoists_keys_into_data_store_prefs() = runTest {
        val source = context.getSharedPreferences(
            LegacyPrefsMigration.LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        source.edit()
            .putBoolean("pref_force_speaker_enabled", false)
            .putLong("pref_force_loop_interval_ms", 250L)
            .putBoolean("pref_safe_mode_requested", true)
            .putInt("pref_crash_retention_days", 7)
            .putString("pref_log_level", "DEBUG")
            .commit()

        val migration = LegacyPrefsMigration.build(context)
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val migrated = migration.migrate(emptyPreferences())
        assertEquals(false, migrated[booleanPreferencesKey("force_speaker_enabled")])
        assertEquals(250L, migrated[longPreferencesKey("force_loop_interval_ms")])
        assertEquals(true, migrated[booleanPreferencesKey("safe_mode_requested")])
        assertEquals(7, migrated[intPreferencesKey("crash_retention_days")])
        assertEquals("DEBUG", migrated[stringPreferencesKey("log_level")])

        migration.cleanUp()
        // After cleanup the legacy file should be gone.
        val after = context.getSharedPreferences(
            LegacyPrefsMigration.LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        assertTrue(after.all.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashBundleMigrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun migrate_is_a_noop_when_directory_missing() = runTest {
        val migration = CrashBundleMigration(context, db.crashEventDao())
        assertEquals(0, migration.migrate())
    }

    @Test
    fun migrate_inserts_rows_and_deletes_source_files() = runTest {
        val migration = CrashBundleMigration(context, db.crashEventDao())
        migration.sourceDirectory.mkdirs()
        val bundle = migration.sourceDirectory.resolve("crash-1.json")
        bundle.writeText(
            buildString {
                appendLine("epochMs=1700000000123")
                appendLine("crashType=NATIVE_FAILURE")
                appendLine("signature=libfoo.so+0xabcd")
                appendLine("stackHead=stack-fragment")
                appendLine("processUptimeMs=42000")
                appendLine("consecutiveCrashes=3")
            },
        )

        val migrated = migration.migrate()
        assertEquals(1, migrated)
        assertFalse(bundle.exists())

        val recent = db.crashEventDao().recent(limit = 10)
        assertEquals(1, recent.size)
        val event = recent.first()
        assertEquals(1_700_000_000_123L, event.epochMs)
        assertEquals(com.vyzorix.audiorouter.common.enums.CrashType.NATIVE_FAILURE, event.crashType)
        assertEquals("libfoo.so+0xabcd", event.signature)
        assertEquals(3, event.consecutiveCrashes)
    }
}

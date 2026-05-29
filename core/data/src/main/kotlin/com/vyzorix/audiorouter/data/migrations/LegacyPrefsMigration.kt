package com.vyzorix.audiorouter.data.migrations

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * One-time migration from the pre-Layer-1 `SharedPreferences` file to the
 * AndroidX Preferences DataStore that backs
 * [com.vyzorix.audiorouter.data.datastore.SettingsDataStore].
 *
 * Why this lives in Layer 1: the canonical persistence layer owns the
 * migration story for everything it persists. Wiring the migration into
 * the DataStore builder in Layer 3 (where the consumer lands) would put
 * the migration definition outside the module that owns the schema.
 *
 * Mapping (source `SharedPreferences` key → DataStore key):
 *  - `pref_force_speaker_enabled`     → `force_speaker_enabled`
 *  - `pref_force_loop_interval_ms`    → `force_loop_interval_ms`
 *  - `pref_safe_mode_requested`       → `safe_mode_requested`
 *  - `pref_crash_retention_days`      → `crash_retention_days`
 *  - `pref_log_level`                 → `log_level`
 *
 * Keys not present in the source file are skipped so the DataStore falls
 * back to the [com.vyzorix.audiorouter.data.datastore.SettingsDataStore]
 * defaults.
 *
 * Idempotency: [cleanUp] deletes the legacy file on success, so subsequent
 * cold starts short-circuit via [shouldMigrate] returning `false`.
 *
 * Wire this into the DataStore builder for `SettingsDataStore` when the
 * consumer lands in Layer 3 — example:
 *
 * ```kotlin
 * preferencesDataStore(
 *     name = SettingsDataStore.DATASTORE_NAME,
 *     produceMigrations = { ctx -> listOf(LegacyPrefsMigration.build(ctx)) },
 * )
 * ```
 */
public object LegacyPrefsMigration {

    /** Source SharedPreferences filename. Matches the pre-Layer-1 default. */
    public const val LEGACY_PREFS_NAME: String = "vyzorix_audiorouter_prefs"

    /** Returns a Layer-1-owned [DataMigration] that hoists keys into DataStore. */
    public fun build(context: Context): DataMigration<Preferences> = LegacyPrefsDataMigration(
        context.applicationContext,
    )
}

private class LegacyPrefsDataMigration(
    private val appContext: Context,
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val prefs = appContext.getSharedPreferences(
            LegacyPrefsMigration.LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        return prefs.all.isNotEmpty()
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val source = appContext.getSharedPreferences(
            LegacyPrefsMigration.LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val mutable = currentData.toMutablePreferences()

        source.all["pref_force_speaker_enabled"]?.let { value ->
            if (value is Boolean) {
                mutable[booleanPreferencesKey("force_speaker_enabled")] = value
            }
        }
        source.all["pref_force_loop_interval_ms"]?.let { value ->
            when (value) {
                is Long -> mutable[longPreferencesKey("force_loop_interval_ms")] = value
                is Int -> mutable[longPreferencesKey("force_loop_interval_ms")] = value.toLong()
                else -> Unit
            }
        }
        source.all["pref_safe_mode_requested"]?.let { value ->
            if (value is Boolean) {
                mutable[booleanPreferencesKey("safe_mode_requested")] = value
            }
        }
        source.all["pref_crash_retention_days"]?.let { value ->
            if (value is Int) {
                mutable[intPreferencesKey("crash_retention_days")] = value
            }
        }
        source.all["pref_log_level"]?.let { value ->
            if (value is String) {
                mutable[stringPreferencesKey("log_level")] = value
            }
        }
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() {
        // Best-effort delete of the legacy file.
        appContext.deleteSharedPreferences(LegacyPrefsMigration.LEGACY_PREFS_NAME)
    }
}

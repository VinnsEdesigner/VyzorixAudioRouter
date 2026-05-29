package com.vyzorix.audiorouter.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistent user/operator-tunable settings.
 *
 * Backed by AndroidX Preferences DataStore for Layer 1. The on-disk schema
 * is a flat key/value map — every setting has a typed key in the
 * [Keys] companion object so misspellings are compile-time errors and the
 * set of persisted settings is enumerable.
 *
 * Why not proto-DataStore? Proto would give us a typed schema document, but
 * also forces a `.proto` build step and a schema-evolution discipline
 * (deprecating fields, reserving numbers) that the daemon's tiny settings
 * surface doesn't justify yet. We can graduate to proto-DataStore in a
 * later layer once the schema stabilises; until then, Preferences keeps
 * the build graph simple.
 *
 * Threading: every read is a [Flow] (suspending collection on the caller).
 * Writes go through [DataStore.edit] which serializes on its own dispatcher.
 * Callers MUST NOT block on suspending reads/writes on the main thread.
 */
public class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {

    // ---- Force-loop toggles ----

    /** Master switch for the speaker-force loop. */
    public val forceSpeakerEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.FORCE_SPEAKER_ENABLED] ?: DEFAULT_FORCE_SPEAKER_ENABLED }

    public suspend fun setForceSpeakerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.FORCE_SPEAKER_ENABLED] = enabled }
    }

    /** Interval between force-loop reassertions, in milliseconds. */
    public val forceLoopIntervalMs: Flow<Long> =
        dataStore.data.map { it[Keys.FORCE_LOOP_INTERVAL_MS] ?: DEFAULT_FORCE_LOOP_INTERVAL_MS }

    public suspend fun setForceLoopIntervalMs(intervalMs: Long) {
        dataStore.edit { it[Keys.FORCE_LOOP_INTERVAL_MS] = intervalMs }
    }

    // ---- Safe mode ----

    /** Operator-driven safe-mode toggle. Distinct from `SAFE_MODE` daemon state. */
    public val safeModeRequested: Flow<Boolean> =
        dataStore.data.map { it[Keys.SAFE_MODE_REQUESTED] ?: false }

    public suspend fun setSafeModeRequested(requested: Boolean) {
        dataStore.edit { it[Keys.SAFE_MODE_REQUESTED] = requested }
    }

    // ---- Crash retention ----

    /** Retention window (days) for `crash_events` rows before `pruneOlderThan`. */
    public val crashRetentionDays: Flow<Int> =
        dataStore.data.map { it[Keys.CRASH_RETENTION_DAYS] ?: DEFAULT_CRASH_RETENTION_DAYS }

    public suspend fun setCrashRetentionDays(days: Int) {
        dataStore.edit { it[Keys.CRASH_RETENTION_DAYS] = days }
    }

    // ---- Logging ----

    /** Minimum log level the on-disk `FileLogger` will accept. */
    public val logLevel: Flow<String> =
        dataStore.data.map { it[Keys.LOG_LEVEL] ?: DEFAULT_LOG_LEVEL }

    public suspend fun setLogLevel(level: String) {
        dataStore.edit { it[Keys.LOG_LEVEL] = level }
    }

    // ---- Bulk read for boot-time hydration ----

    /**
     * Returns a one-shot snapshot of every setting. Used by
     * `BootStateRestorer` (later layer) to hydrate in-memory caches
     * before the service flips to `RUNNING`.
     */
    public suspend fun snapshot(): SettingsSnapshot {
        val prefs = dataStore.data.first()
        return SettingsSnapshot(
            forceSpeakerEnabled = prefs[Keys.FORCE_SPEAKER_ENABLED] ?: DEFAULT_FORCE_SPEAKER_ENABLED,
            forceLoopIntervalMs = prefs[Keys.FORCE_LOOP_INTERVAL_MS] ?: DEFAULT_FORCE_LOOP_INTERVAL_MS,
            safeModeRequested = prefs[Keys.SAFE_MODE_REQUESTED] ?: false,
            crashRetentionDays = prefs[Keys.CRASH_RETENTION_DAYS] ?: DEFAULT_CRASH_RETENTION_DAYS,
            logLevel = prefs[Keys.LOG_LEVEL] ?: DEFAULT_LOG_LEVEL,
        )
    }

    /** Wipes every key managed by this store — used by factory-reset paths. */
    public suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    public companion object {
        public const val DATASTORE_NAME: String = "settings"

        public const val DEFAULT_FORCE_SPEAKER_ENABLED: Boolean = true
        public const val DEFAULT_FORCE_LOOP_INTERVAL_MS: Long = 500L
        public const val DEFAULT_CRASH_RETENTION_DAYS: Int = 30
        public const val DEFAULT_LOG_LEVEL: String = "INFO"

        public object Keys {
            public val FORCE_SPEAKER_ENABLED: Preferences.Key<Boolean> =
                booleanPreferencesKey("force_speaker_enabled")
            public val FORCE_LOOP_INTERVAL_MS: Preferences.Key<Long> =
                longPreferencesKey("force_loop_interval_ms")
            public val SAFE_MODE_REQUESTED: Preferences.Key<Boolean> =
                booleanPreferencesKey("safe_mode_requested")
            public val CRASH_RETENTION_DAYS: Preferences.Key<Int> =
                intPreferencesKey("crash_retention_days")
            public val LOG_LEVEL: Preferences.Key<String> =
                stringPreferencesKey("log_level")
        }
    }
}

/** Frozen view of every setting in [SettingsDataStore]. */
public data class SettingsSnapshot(
    val forceSpeakerEnabled: Boolean,
    val forceLoopIntervalMs: Long,
    val safeModeRequested: Boolean,
    val crashRetentionDays: Int,
    val logLevel: String,
)

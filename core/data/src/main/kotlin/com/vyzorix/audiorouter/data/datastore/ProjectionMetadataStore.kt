package com.vyzorix.audiorouter.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistence-only metadata about the most recent MediaProjection session.
 *
 * Stores only descriptive fields (start time, capture format, originating
 * trigger). Crucially, this store DOES NOT persist a MediaProjection
 * `Intent` token, `resultCode`, or any other revivable handle — those
 * are per-grant ephemera and Android forbids reusing them across process
 * deaths. Layer 3's `TrampolineActivity` re-acquires the token on every
 * cold start; this store only persists what's safe.
 *
 * Consumers (Layer 5+):
 *  - `DaemonStatusAggregator` reads the latest capture format to surface
 *    "currently capturing at 48 kHz / 16-bit" on the dashboard.
 *  - `RouteForensicsReporter` reads the start-epoch to correlate against
 *    `route_history` rows when assembling a forensic bundle.
 */
public class ProjectionMetadataStore(
    private val dataStore: DataStore<Preferences>,
) {

    public val lastSessionStartEpochMs: Flow<Long?> =
        dataStore.data.map { it[Keys.LAST_SESSION_START_EPOCH_MS] }

    public val lastSessionStopEpochMs: Flow<Long?> =
        dataStore.data.map { it[Keys.LAST_SESSION_STOP_EPOCH_MS] }

    public val lastSampleRateHz: Flow<Int?> =
        dataStore.data.map { it[Keys.LAST_SAMPLE_RATE_HZ] }

    public val lastChannelCount: Flow<Int?> =
        dataStore.data.map { it[Keys.LAST_CHANNEL_COUNT] }

    public val lastTriggerOrigin: Flow<String?> =
        dataStore.data.map { it[Keys.LAST_TRIGGER_ORIGIN] }

    /**
     * Atomically records that a projection session started. Caller passes
     * the wall-clock epoch + the capture format that the system granted.
     */
    public suspend fun recordSessionStart(
        startEpochMs: Long,
        sampleRateHz: Int,
        channelCount: Int,
        triggerOrigin: String,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SESSION_START_EPOCH_MS] = startEpochMs
            prefs[Keys.LAST_SAMPLE_RATE_HZ] = sampleRateHz
            prefs[Keys.LAST_CHANNEL_COUNT] = channelCount
            prefs[Keys.LAST_TRIGGER_ORIGIN] = triggerOrigin
            prefs.remove(Keys.LAST_SESSION_STOP_EPOCH_MS)
        }
    }

    /** Records the wall-clock epoch at which the session ended. */
    public suspend fun recordSessionStop(stopEpochMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SESSION_STOP_EPOCH_MS] = stopEpochMs
        }
    }

    /** One-shot snapshot of the persisted metadata. */
    public suspend fun snapshot(): ProjectionMetadataSnapshot {
        val prefs = dataStore.data.first()
        return ProjectionMetadataSnapshot(
            lastSessionStartEpochMs = prefs[Keys.LAST_SESSION_START_EPOCH_MS],
            lastSessionStopEpochMs = prefs[Keys.LAST_SESSION_STOP_EPOCH_MS],
            lastSampleRateHz = prefs[Keys.LAST_SAMPLE_RATE_HZ],
            lastChannelCount = prefs[Keys.LAST_CHANNEL_COUNT],
            lastTriggerOrigin = prefs[Keys.LAST_TRIGGER_ORIGIN],
        )
    }

    public suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    public companion object {
        public const val DATASTORE_NAME: String = "projection_metadata"

        public object Keys {
            public val LAST_SESSION_START_EPOCH_MS: Preferences.Key<Long> =
                longPreferencesKey("last_session_start_epoch_ms")
            public val LAST_SESSION_STOP_EPOCH_MS: Preferences.Key<Long> =
                longPreferencesKey("last_session_stop_epoch_ms")
            public val LAST_SAMPLE_RATE_HZ: Preferences.Key<Int> =
                intPreferencesKey("last_sample_rate_hz")
            public val LAST_CHANNEL_COUNT: Preferences.Key<Int> =
                intPreferencesKey("last_channel_count")
            public val LAST_TRIGGER_ORIGIN: Preferences.Key<String> =
                stringPreferencesKey("last_trigger_origin")
        }
    }
}

/** Frozen view of every field in [ProjectionMetadataStore]. */
public data class ProjectionMetadataSnapshot(
    val lastSessionStartEpochMs: Long?,
    val lastSessionStopEpochMs: Long?,
    val lastSampleRateHz: Int?,
    val lastChannelCount: Int?,
    val lastTriggerOrigin: String?,
)

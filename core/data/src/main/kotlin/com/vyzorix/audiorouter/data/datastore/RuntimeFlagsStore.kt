package com.vyzorix.audiorouter.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Dynamic feature flags. Separate from [SettingsDataStore] because flags
 * are server-pushable (via the C2 channel) and have different write
 * authorisation than user-tunable settings.
 *
 * Storage shape:
 *  - One boolean per known flag (typed key in [Keys.WELL_KNOWN]).
 *  - One `Set<String>` of unknown-flag names that arrived from C2 but
 *    don't have a typed binding yet — kept so we can replay them after
 *    a daemon upgrade without losing operator intent.
 *
 * Layer 4+ consumers go through [isEnabled] / [observe] for typed flags;
 * unknown flags are surfaced through [unknownFlags] so a future Layer 8
 * `RemoteFlagSyncer` can reconcile them.
 */
public class RuntimeFlagsStore(
    private val dataStore: DataStore<Preferences>,
) {

    public fun observe(flag: Flag): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[flag.key] ?: flag.default }

    public suspend fun isEnabled(flag: Flag): Boolean = observe(flag).first()

    public suspend fun setEnabled(flag: Flag, enabled: Boolean) {
        dataStore.edit { it[flag.key] = enabled }
    }

    /** Snapshot of every typed flag. */
    public suspend fun snapshot(): Map<Flag, Boolean> {
        val prefs = dataStore.data.first()
        return Flag.values().associateWith { flag -> prefs[flag.key] ?: flag.default }
    }

    // ---- Unknown-flag staging ----

    public val unknownFlags: Flow<Set<String>> =
        dataStore.data.map { it[Keys.UNKNOWN_FLAGS] ?: emptySet() }

    public suspend fun recordUnknownFlag(name: String) {
        dataStore.edit { prefs ->
            val existing = prefs[Keys.UNKNOWN_FLAGS] ?: emptySet()
            prefs[Keys.UNKNOWN_FLAGS] = existing + name
        }
    }

    public suspend fun forgetUnknownFlag(name: String) {
        dataStore.edit { prefs ->
            val existing = prefs[Keys.UNKNOWN_FLAGS] ?: emptySet()
            prefs[Keys.UNKNOWN_FLAGS] = existing - name
        }
    }

    public suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    public companion object {
        public const val DATASTORE_NAME: String = "runtime_flags"

        public object Keys {
            public val UNKNOWN_FLAGS: Preferences.Key<Set<String>> =
                stringSetPreferencesKey("unknown_flags")
        }
    }

    /**
     * Typed flag definitions. Each enum value owns its DataStore key and
     * default. Adding a new flag is one line; renaming requires a migration
     * because the [key] is the on-disk identifier.
     */
    public enum class Flag(
        public val keyName: String,
        public val default: Boolean,
    ) {
        ENABLE_DIAGNOSTIC_OVERLAY("enable_diagnostic_overlay", false),
        AGGRESSIVE_RECOVERY("aggressive_recovery", false),
        VERBOSE_ROUTE_LOGGING("verbose_route_logging", false),
        BLOCK_OUTBOUND_NETWORK("block_outbound_network", false),
        ;

        public val key: Preferences.Key<Boolean> get() = booleanPreferencesKey(keyName)
    }
}

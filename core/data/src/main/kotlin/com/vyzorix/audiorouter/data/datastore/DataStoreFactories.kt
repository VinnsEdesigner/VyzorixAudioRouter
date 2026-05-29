package com.vyzorix.audiorouter.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Per-store AndroidX Preferences DataStore delegates + factory objects.
 *
 * Each delegate is property-extension scoped to [Context] so the
 * AndroidX `preferencesDataStore` library can enforce its per-file
 * process-singleton invariant. The companion objects below are the only
 * call sites permitted to read the delegate property.
 *
 * Listed in one file because the three stores share a single pattern
 * and pulling them apart adds noise without clarifying anything.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsDataStore.DATASTORE_NAME,
)

private val Context.runtimeFlagsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = RuntimeFlagsStore.DATASTORE_NAME,
)

private val Context.projectionMetadataDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ProjectionMetadataStore.DATASTORE_NAME,
)

public object SettingsDataStoreFactory {
    public fun create(context: Context): SettingsDataStore =
        SettingsDataStore(context.applicationContext.settingsDataStore)
}

public object RuntimeFlagsStoreFactory {
    public fun create(context: Context): RuntimeFlagsStore =
        RuntimeFlagsStore(context.applicationContext.runtimeFlagsDataStore)
}

public object ProjectionMetadataStoreFactory {
    public fun create(context: Context): ProjectionMetadataStore =
        ProjectionMetadataStore(context.applicationContext.projectionMetadataDataStore)
}

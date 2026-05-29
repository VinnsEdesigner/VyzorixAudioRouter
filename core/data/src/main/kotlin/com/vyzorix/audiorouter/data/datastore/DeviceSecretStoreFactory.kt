package com.vyzorix.audiorouter.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.vyzorix.audiorouter.common.utils.TokenEncryptor

/**
 * Wires the AndroidX Preferences DataStore singleton extension to a
 * [DeviceSecretStore]. The DataStore file lives at
 * `<filesDir>/datastore/device_secret.preferences_pb` (DOC_7 §3.9).
 *
 * Layer 1 callers should hold one [DeviceSecretStore] per application; the
 * AndroidX `preferencesDataStore` delegate already enforces a single
 * instance per file per process.
 */
public object DeviceSecretStoreFactory {

    /** Same name as the on-disk file (`device_secret.preferences_pb`). */
    private val Context.deviceSecretDataStore: DataStore<Preferences> by preferencesDataStore(
        name = DeviceSecretStore.DATASTORE_NAME,
    )

    public fun create(
        context: Context,
        tokenEncryptor: TokenEncryptor,
    ): DeviceSecretStore = DeviceSecretStore(
        dataStore = context.applicationContext.deviceSecretDataStore,
        tokenEncryptor = tokenEncryptor,
    )
}

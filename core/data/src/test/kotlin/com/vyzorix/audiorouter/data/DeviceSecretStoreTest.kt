package com.vyzorix.audiorouter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.utils.SecretIntegrityException
import com.vyzorix.audiorouter.common.utils.SoftwareKeystoreManager
import com.vyzorix.audiorouter.common.utils.TokenEncryptor
import com.vyzorix.audiorouter.data.datastore.DeviceSecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceSecretStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun get_secret_is_null_before_put() = runTest {
        val store = newStore()
        assertNull(store.getSecret())
        assertFalse(store.hasSecret.first())
    }

    @Test
    fun put_then_get_returns_same_secret() = runTest {
        val store = newStore()
        val secret = "deadbeef".repeat(8) // 64 hex chars
        store.put(secret)

        assertEquals(secret, store.getSecret())
        assertTrue(store.hasSecret.first())
    }

    @Test
    fun clear_removes_stored_blob() = runTest {
        val store = newStore()
        store.put("some-secret")
        assertTrue(store.hasSecret.first())

        store.clear()

        assertNull(store.getSecret())
        assertFalse(store.hasSecret.first())
    }

    @Test
    fun secret_is_encrypted_on_disk_not_plaintext() = runTest {
        // Use a real on-disk file so we can scan its bytes for the plaintext.
        val storeFile = File(context.filesDir, "device_secret_test_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(produceFile = { storeFile })
        val encryptor = TokenEncryptor(SoftwareKeystoreManager.create(context, "dss_${System.nanoTime()}"))
        val store = DeviceSecretStore(dataStore, encryptor)

        val secret = "absolutely-secret-do-not-leak-this"
        store.put(secret)

        // Read the raw bytes back. The plaintext MUST NOT appear anywhere.
        val onDisk = storeFile.readBytes()
        val plaintextBytes = secret.toByteArray()
        assertFalse(
            onDisk.indexOf(plaintextBytes) >= 0,
            "DeviceSecretStore leaked plaintext to ${storeFile.name}",
        )

        // Sanity: round-trip still works.
        assertEquals(secret, store.getSecret())

        storeFile.delete()
    }

    @Test
    fun get_secret_throws_when_blob_is_tampered() = runTest {
        // Mutate the sealed blob in-place through the DataStore so we don't
        // need to spin up a second DataStore over the same file (DataStore
        // is a process-wide singleton per file and rejects duplicates).
        val storeFile = File(context.filesDir, "tampered_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(produceFile = { storeFile })
        val encryptor = TokenEncryptor(SoftwareKeystoreManager.create(context, "dss_${System.nanoTime()}"))
        val store = DeviceSecretStore(dataStore, encryptor)
        store.put("original")

        // Flip one hex char in the persisted sealed blob — that's enough to
        // fail the AEAD tag check while keeping the value valid hex.
        dataStore.edit { prefs ->
            val sealed = prefs[DeviceSecretStore.KEY_SECRET_SEALED]
                ?: error("expected sealed blob in DataStore after put()")
            val tampered = sealed.dropLast(1) + (if (sealed.last() == '0') '1' else '0')
            prefs[DeviceSecretStore.KEY_SECRET_SEALED] = tampered
        }

        assertFailsWith<SecretIntegrityException> { store.getSecret() }

        storeFile.delete()
    }

    private fun newStore(): DeviceSecretStore {
        // PreferenceDataStoreFactory.create() with a fresh File per call so
        // tests don't share state. We can't use the `preferencesDataStore`
        // delegate here because it's process-scoped.
        val storeFile = File(context.filesDir, "secret_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(produceFile = { storeFile })
        val encryptor = TokenEncryptor(SoftwareKeystoreManager.create(context, "dss_${System.nanoTime()}"))
        return DeviceSecretStore(dataStore, encryptor)
    }
}

private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

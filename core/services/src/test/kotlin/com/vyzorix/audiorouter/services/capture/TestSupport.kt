// Test-only helpers shared across the capture/ test suite.

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.common.utils.KeystoreManager
import com.vyzorix.audiorouter.common.utils.TokenEncryptor
import com.vyzorix.audiorouter.data.datastore.ProjectionMetadataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** Identity keystore — no encryption; perfect for fast unit tests. */
internal class IdentityKeystoreManager : KeystoreManager {
    override val isHardwareBacked: Boolean = false
    override fun seal(plaintext: ByteArray): String =
        plaintext.joinToString("") { String.format("%02x", it) }
    override fun unseal(sealed: String): ByteArray {
        require(sealed.length % 2 == 0) { "odd-length hex blob" }
        return ByteArray(sealed.length / 2) { i ->
            sealed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/** Builds a fake [TokenEncryptor] backed by [IdentityKeystoreManager]. */
internal fun fakeTokenEncryptor(): TokenEncryptor =
    TokenEncryptor(keystoreManager = IdentityKeystoreManager())

/** In-memory `DataStore<Preferences>` for [ProjectionMetadataStore] tests. */
internal class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val flow = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: kotlinx.coroutines.flow.Flow<Preferences> = flow

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val next = transform(flow.value)
        flow.value = next
        return next
    }
}

internal fun inMemoryProjectionMetadataStore(): ProjectionMetadataStore =
    ProjectionMetadataStore(dataStore = InMemoryPreferencesDataStore())

internal fun fakeCapturePermissionStore(): CapturePermissionStore =
    CapturePermissionStore(projectionMetadataStore = inMemoryProjectionMetadataStore())

internal fun fakeProjectionTokenManager(scope: CoroutineScope): ProjectionTokenManager =
    ProjectionTokenManager(
        scope = scope,
        permissionStore = fakeCapturePermissionStore(),
        tokenPersistence = TokenPersistence(tokenEncryptor = fakeTokenEncryptor()),
    )

@Suppress("UnusedPrivateMember")
private fun touchPrefsApi(): Preferences = mutablePreferencesOf()

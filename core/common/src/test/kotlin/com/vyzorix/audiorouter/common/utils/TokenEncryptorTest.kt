package com.vyzorix.audiorouter.common.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `TokenEncryptor` is a thin adapter over `KeystoreManager.seal/unseal`,
 * so we exercise it against a real (software-backed) keystore manager
 * rather than a mock. That way the encrypt/decrypt round-trip is end-to-end
 * AES-GCM, which is what `DeviceSecretStore` will see at runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenEncryptorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun encrypt_decrypt_round_trip_preserves_string() {
        val keystore = SoftwareKeystoreManager.create(context, "te_${System.nanoTime()}")
        val encryptor = TokenEncryptor(keystore)

        val plaintext = "abcdef" + "0".repeat(58) // shape of a 64-hex secret
        val sealed = encryptor.encrypt(plaintext)
        val recovered = encryptor.decrypt(sealed)

        assertEquals(plaintext, recovered)
    }

    @Test
    fun decrypt_throws_SecretIntegrityException_on_tampered_blob() {
        val keystore = SoftwareKeystoreManager.create(context, "te_${System.nanoTime()}")
        val encryptor = TokenEncryptor(keystore)

        val sealed = encryptor.encrypt("real secret")
        val tampered = sealed.dropLast(1) + (if (sealed.last() == '0') '1' else '0')

        assertFailsWith<SecretIntegrityException> { encryptor.decrypt(tampered) }
    }
}

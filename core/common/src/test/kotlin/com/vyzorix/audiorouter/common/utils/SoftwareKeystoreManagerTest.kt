package com.vyzorix.audiorouter.common.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SoftwareKeystoreManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun seal_then_unseal_round_trips_plaintext() {
        val manager = SoftwareKeystoreManager.create(context, freshPrefs())
        val plaintext = "the quick brown fox".toByteArray()

        val sealed = manager.seal(plaintext)
        val recovered = manager.unseal(sealed)

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun isHardwareBacked_is_false() {
        val manager = SoftwareKeystoreManager.create(context, freshPrefs())
        assertFalse(manager.isHardwareBacked)
    }

    @Test
    fun seal_is_non_deterministic_for_same_plaintext() {
        val manager = SoftwareKeystoreManager.create(context, freshPrefs())
        val plaintext = "deterministic-input".toByteArray()

        // GCM uses a fresh IV per seal call; two sealings must differ.
        val a = manager.seal(plaintext)
        val b = manager.seal(plaintext)
        assertNotEquals(a, b)
    }

    @Test
    fun unseal_after_persisted_prefs_survives_new_instance() {
        val prefsName = freshPrefs()
        val plaintext = "round-trip-across-instances".toByteArray()

        val sealed = SoftwareKeystoreManager.create(context, prefsName).seal(plaintext)

        // A second instance over the same prefs must derive the same key.
        val recovered = SoftwareKeystoreManager.create(context, prefsName).unseal(sealed)
        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun unseal_rejects_tampered_blob() {
        val manager = SoftwareKeystoreManager.create(context, freshPrefs())
        val sealed = manager.seal("payload".toByteArray())

        // Flip the last hex char to force an AEAD tag mismatch.
        val tampered = sealed.dropLast(1) + (if (sealed.last() == '0') '1' else '0')

        val ex = assertFailsWith<KeystoreFailureException> { manager.unseal(tampered) }
        // The root cause MUST be the AEAD tag check, not a generic decode error.
        val rootCauseChain = generateSequence(ex as Throwable) { it.cause }.toList()
        assertTrue(
            rootCauseChain.any { it is AEADBadTagException },
            "expected AEADBadTagException in cause chain, got: $rootCauseChain",
        )
    }

    /** Use a unique prefs name per test so Robolectric's in-memory prefs don't bleed. */
    private fun freshPrefs(): String = "test_${System.nanoTime()}"
}

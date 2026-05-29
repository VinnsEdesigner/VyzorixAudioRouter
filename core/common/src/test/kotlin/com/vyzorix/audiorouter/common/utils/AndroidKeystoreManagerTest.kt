package com.vyzorix.audiorouter.common.utils

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Robolectric does NOT ship a working `AndroidKeyStore` provider — see
 * https://github.com/robolectric/robolectric/issues/1518 (long-standing,
 * unresolved). The shadow refuses to load the provider, which means these
 * tests can only meaningfully run on an actual device or an emulator with
 * a real `AndroidKeyStore` registered.
 *
 * Rather than `@Ignore`-ing them outright we use [assumeTrue] so that if a
 * future Robolectric release lights up the shadow, the tests start running
 * automatically. On-device coverage is the canonical acceptance gate per
 * doc/BUILD_ORDER.md Layer 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidKeystoreManagerTest {

    @Before
    fun skipIfAndroidKeyStoreNotAvailable() {
        val providerAvailable = runCatching {
            KeyStore.getInstance("AndroidKeyStore").load(null)
        }.isSuccess
        assumeTrue(
            "AndroidKeyStore provider not available under the current test runner",
            providerAvailable,
        )
    }

    @Test
    fun isHardwareBacked_is_true_by_contract() {
        val manager = AndroidKeystoreManager(uniqueAlias())
        // The shadow doesn't actually back onto a TEE — but the *contract*
        // for this class is that it's the hardware-backed path. The
        // SoftwareKeystoreManager fallback is the false branch.
        assertTrue(manager.isHardwareBacked)
    }

    @Test
    fun seal_then_unseal_round_trips() {
        val manager = AndroidKeystoreManager(uniqueAlias())
        val plaintext = "androidkeystore-payload".toByteArray()

        val sealed = manager.seal(plaintext)
        val recovered = manager.unseal(sealed)

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun second_instance_reuses_persisted_alias() {
        val alias = uniqueAlias()
        val plaintext = "reuse-alias".toByteArray()

        val sealed = AndroidKeystoreManager(alias).seal(plaintext)
        val recovered = AndroidKeystoreManager(alias).unseal(sealed)

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun unseal_rejects_tampered_blob() {
        val manager = AndroidKeystoreManager(uniqueAlias())
        val sealed = manager.seal("payload".toByteArray())

        val tampered = sealed.dropLast(1) + (if (sealed.last() == '0') '1' else '0')

        val ex = assertFailsWith<KeystoreFailureException> { manager.unseal(tampered) }
        val rootCauseChain = generateSequence(ex as Throwable) { it.cause }.toList()
        assertTrue(
            rootCauseChain.any { it is AEADBadTagException },
            "expected AEADBadTagException in cause chain, got: $rootCauseChain",
        )
    }

    private fun uniqueAlias(): String = "test-alias-${System.nanoTime()}"
}

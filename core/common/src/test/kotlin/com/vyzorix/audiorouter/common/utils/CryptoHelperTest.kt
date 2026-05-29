package com.vyzorix.audiorouter.common.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CryptoHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadOrProvision_creates_passphrase_on_first_call() {
        val helper = newHelper()

        val first = helper.loadOrProvisionDatabasePassphrase()
        assertEquals(CryptoHelper.PASSPHRASE_BYTES, first.size)
        // Must not be all-zero — that would mean SecureRandom didn't run.
        assertNotNull(first.firstOrNull { it != 0.toByte() })
    }

    @Test
    fun loadOrProvision_returns_same_passphrase_across_calls() {
        val helper = newHelper()

        val a = helper.loadOrProvisionDatabasePassphrase()
        val b = helper.loadOrProvisionDatabasePassphrase()

        assertContentEquals(a, b)
    }

    @Test
    fun clear_forces_new_passphrase_on_next_call() {
        val helper = newHelper()

        val a = helper.loadOrProvisionDatabasePassphrase()
        helper.clearDatabasePassphrase()
        val b = helper.loadOrProvisionDatabasePassphrase()

        // Cryptographically vanishing chance of a collision; treat equality
        // as a regression in clearDatabasePassphrase().
        assertTrue(a.indices.any { a[it] != b[it] }, "expected a fresh passphrase after clear()")
    }

    private fun newHelper(): CryptoHelper {
        val keystorePrefsName = "ck_${System.nanoTime()}"
        val dbPrefsName = "db_${System.nanoTime()}"
        val keystore = SoftwareKeystoreManager.create(context, keystorePrefsName)
        val dbPrefs = context.getSharedPreferences(dbPrefsName, Context.MODE_PRIVATE)
        return CryptoHelper(keystore, dbPrefs)
    }
}

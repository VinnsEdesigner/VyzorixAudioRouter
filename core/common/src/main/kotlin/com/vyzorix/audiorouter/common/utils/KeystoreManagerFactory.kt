package com.vyzorix.audiorouter.common.utils

import android.content.Context
import com.vyzorix.audiorouter.common.device.DeviceQuirkProfile
import com.vyzorix.audiorouter.common.device.KeystoreReliability

/**
 * Picks the right [KeystoreManager] back-end for the running device.
 *
 * Selection rule (see doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.1):
 *  - `RELIABLE` → [AndroidKeystoreManager]
 *  - `UNRELIABLE_USE_SOFTWARE_FALLBACK` → [SoftwareKeystoreManager]
 *
 * Additionally, if [AndroidKeystoreManager] throws on first use (e.g. the
 * `AndroidKeyStore` provider is missing — happens on stripped-down emulator
 * images and in some Robolectric configurations), the factory transparently
 * falls back to [SoftwareKeystoreManager]. Callers see a working manager;
 * the demotion is logged via the supplied [logger].
 */
public object KeystoreManagerFactory {

    /** Trigger string a [logger] receives when the factory demotes to the software path. */
    public const val DEMOTION_LOG_MESSAGE: String =
        "AndroidKeystore unavailable; demoting to SoftwareKeystoreManager"

    public fun create(
        context: Context,
        profile: DeviceQuirkProfile,
        logger: (String) -> Unit = {},
    ): KeystoreManager {
        return when (profile.keystoreReliability) {
            KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK ->
                SoftwareKeystoreManager.create(context)
            KeystoreReliability.RELIABLE -> tryAndroidOrFallback(context, logger)
        }
    }

    private fun tryAndroidOrFallback(
        context: Context,
        logger: (String) -> Unit,
    ): KeystoreManager {
        val candidate = AndroidKeystoreManager(AndroidKeystoreManager.DEFAULT_KEY_ALIAS)
        return try {
            // Force a roundtrip so a broken provider fails NOW, not on the
            // first real seal call deep inside startup.
            val probe = candidate.seal(byteArrayOf(0x00))
            candidate.unseal(probe)
            candidate
        } catch (e: KeystoreFailureException) {
            logger("$DEMOTION_LOG_MESSAGE: ${e.message}")
            SoftwareKeystoreManager.create(context)
        }
    }
}

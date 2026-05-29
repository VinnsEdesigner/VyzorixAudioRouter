// DaemonStorageProvider — lazy assembly of the daemon's Room database and
// the [DaemonStateRepository] used by [SpeakerForceManager].
//
// Why a separate class (vs inlining in PersistentAudioService.onCreate):
//   - The wiring chain (KeystoreManager → CryptoHelper → AppDatabaseFactory)
//     is non-trivial; the service should not have to know any of it.
//   - The chain is expensive on first call (SQLCipher passphrase derivation
//     + Room schema initialisation), so it is deferred behind a lazy and
//     resolved on the first persistence write rather than blocking
//     onCreate.
//   - Tests can swap the provider with a stub that yields an in-memory
//     repository without touching the disk path.

package com.vyzorix.audiorouter.services.state

import android.content.Context
import com.vyzorix.audiorouter.common.utils.CryptoHelper
import com.vyzorix.audiorouter.common.utils.KeystoreManagerFactory
import com.vyzorix.audiorouter.data.database.AppDatabase
import com.vyzorix.audiorouter.data.database.AppDatabaseFactory
import com.vyzorix.audiorouter.data.repository.DaemonStateRepository
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile

/** Owns the daemon's persistent storage handle. */
public class DaemonStorageProvider(
    context: Context,
    private val profile: NokiaC22DeviceProfile = NokiaC22DeviceProfile.current(),
) {

    private val appContext: Context = context.applicationContext

    private val database: AppDatabase by lazy {
        val keystoreManager = KeystoreManagerFactory.create(
            context = appContext,
            profile = profile.rawProfile,
        )
        val prefs = appContext.getSharedPreferences(
            CryptoHelper.DEFAULT_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val cryptoHelper = CryptoHelper(keystoreManager, prefs)
        AppDatabaseFactory.build(
            context = appContext,
            cryptoHelper = cryptoHelper,
        )
    }

    /** Repository over the persisted [daemon_state] table. */
    public val daemonStateRepository: DaemonStateRepository by lazy {
        DaemonStateRepository(database.daemonStateDao())
    }
}

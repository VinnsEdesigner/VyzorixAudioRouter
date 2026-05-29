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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.vyzorix.audiorouter.common.utils.CryptoHelper
import com.vyzorix.audiorouter.common.utils.KeystoreManager
import com.vyzorix.audiorouter.common.utils.KeystoreManagerFactory
import com.vyzorix.audiorouter.common.utils.TokenEncryptor
import com.vyzorix.audiorouter.data.database.AppDatabase
import com.vyzorix.audiorouter.data.database.AppDatabaseFactory
import com.vyzorix.audiorouter.data.datastore.ProjectionMetadataStore
import com.vyzorix.audiorouter.data.repository.DaemonStateRepository
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile

private val Context.projectionMetadataDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "projection_metadata",
)

/** Owns the daemon's persistent storage handle. */
public class DaemonStorageProvider(
    context: Context,
    private val profile: NokiaC22DeviceProfile = NokiaC22DeviceProfile.current(),
) {

    private val appContext: Context = context.applicationContext

    private val keystoreManager: KeystoreManager by lazy {
        KeystoreManagerFactory.create(
            context = appContext,
            profile = profile.rawProfile,
        )
    }

    private val database: AppDatabase by lazy {
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

    /** Layer 4 — projection metadata store backed by Preferences DataStore. */
    public val projectionMetadataStore: ProjectionMetadataStore by lazy {
        ProjectionMetadataStore(dataStore = appContext.projectionMetadataDataStore)
    }

    /** Layer 4 — AES-GCM encryptor backed by the same KeystoreManager. */
    public val tokenEncryptor: TokenEncryptor by lazy {
        TokenEncryptor(keystoreManager = keystoreManager)
    }
}

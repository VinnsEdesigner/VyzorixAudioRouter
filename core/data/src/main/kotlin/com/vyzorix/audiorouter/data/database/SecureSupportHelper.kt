package com.vyzorix.audiorouter.data.database

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Bridges SQLCipher's encrypted SQLite driver into Room's
 * [SupportSQLiteOpenHelper.Factory] contract.
 *
 * The passphrase bytes are sourced from
 * [com.vyzorix.audiorouter.common.utils.CryptoHelper.loadOrProvisionDatabasePassphrase]
 * — see doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §1 for the full
 * key-flow diagram.
 *
 * SQLCipher's native library is loaded lazily on first DB open. Callers
 * SHOULD invoke [loadNativeLibraries] from the daemon's startup path
 * (see Layer 3 bootstrap, future work) so the first `Room.databaseBuilder`
 * call doesn't pay the load cost on whichever dispatcher happens to be
 * hot.
 *
 * Important: this factory takes a *copy* of the passphrase bytes and clears
 * the caller-supplied array. The caller MUST treat the passphrase as
 * write-once. Failing to do so leaves a copy of the master key in any
 * post-call heap dump.
 */
public object SecureSupportHelper {

    /**
     * Returns a Room-compatible [SupportSQLiteOpenHelper.Factory] keyed by
     * [passphrase]. The byte array is zeroed before this function returns.
     */
    public fun factory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        val copy = passphrase.copyOf()
        try {
            // SupportOpenHelperFactory holds the bytes for the lifetime of
            // the helper; it will zero them when the helper is closed.
            return SupportOpenHelperFactory(copy)
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * Optional warm-up — load the SQLCipher native library before the first
     * `Room.databaseBuilder` call. No-op on platforms where the library is
     * already loaded.
     */
    public fun loadNativeLibraries() {
        System.loadLibrary("sqlcipher")
    }
}

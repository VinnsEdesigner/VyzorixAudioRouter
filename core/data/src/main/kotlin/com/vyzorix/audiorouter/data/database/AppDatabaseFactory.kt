package com.vyzorix.audiorouter.data.database

import android.content.Context
import androidx.room.Room
import com.vyzorix.audiorouter.common.utils.CryptoHelper

/**
 * Top-level builder for [AppDatabase]. Wires together
 * [com.vyzorix.audiorouter.common.utils.KeystoreManager] (master key) →
 * [CryptoHelper] (database passphrase) → [SecureSupportHelper] (SQLCipher
 * support factory) → Room.
 *
 * Callers above Layer 1 (services, app) should depend on this factory rather
 * than on Room directly — that way the cipher wiring stays in one place and
 * a future swap of the passphrase derivation strategy is local.
 */
public object AppDatabaseFactory {

    public fun build(
        context: Context,
        cryptoHelper: CryptoHelper,
        databaseName: String = AppDatabase.DATABASE_NAME,
    ): AppDatabase {
        val passphrase = cryptoHelper.loadOrProvisionDatabasePassphrase()
        val openHelperFactory = SecureSupportHelper.factory(passphrase)
        // `factory()` zeroes the caller-supplied passphrase bytes; we drop
        // our reference here for completeness — there are no other refs.
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            databaseName,
        )
            .openHelperFactory(openHelperFactory)
            .addMigrations(*AppDatabaseMigrations.ALL)
            // Note: no destructive-migration fallback is wired up. Upgrades
            // MUST ship explicit migrations; downgrades throw. We want PRs
            // to fail loudly rather than silently dropping user data.
            .build()
    }
}

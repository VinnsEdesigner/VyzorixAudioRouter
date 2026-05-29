package com.vyzorix.audiorouter.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vyzorix.audiorouter.data.dao.CrashEventDao
import com.vyzorix.audiorouter.data.dao.DaemonStateDao
import com.vyzorix.audiorouter.data.dao.UpdateStateDao
import com.vyzorix.audiorouter.data.entity.CrashEventEntity
import com.vyzorix.audiorouter.data.entity.DaemonStateEntity
import com.vyzorix.audiorouter.data.entity.UpdateStateEntity

/**
 * Encrypted Room database for the daemon. SQLCipher full-DB encryption per
 * ADR-0004; passphrase is held by [com.vyzorix.audiorouter.common.utils.CryptoHelper].
 *
 * Schema is exported under `core/data/schemas/` (KSP arg `room.schemaLocation`)
 * so any future migration shows up as a reviewable file in PRs.
 */
@Database(
    entities = [
        DaemonStateEntity::class,
        CrashEventEntity::class,
        UpdateStateEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
public abstract class AppDatabase : RoomDatabase() {

    public abstract fun daemonStateDao(): DaemonStateDao
    public abstract fun crashEventDao(): CrashEventDao
    public abstract fun updateStateDao(): UpdateStateDao

    public companion object {
        public const val DATABASE_NAME: String = "vyzorix_audiorouter.db"
        public const val VERSION: Int = 1
    }
}

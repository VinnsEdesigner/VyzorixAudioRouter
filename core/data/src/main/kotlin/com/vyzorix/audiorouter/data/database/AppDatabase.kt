package com.vyzorix.audiorouter.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vyzorix.audiorouter.data.converters.AudioRouteTypeConverters
import com.vyzorix.audiorouter.data.converters.CrashEventTypeConverters
import com.vyzorix.audiorouter.data.converters.DaemonStateTypeConverters
import com.vyzorix.audiorouter.data.converters.DateTimeTypeConverters
import com.vyzorix.audiorouter.data.converters.PermissionGrantTypeConverters
import com.vyzorix.audiorouter.data.converters.UpdateStateTypeConverters
import com.vyzorix.audiorouter.data.dao.CrashEventDao
import com.vyzorix.audiorouter.data.dao.DaemonStateDao
import com.vyzorix.audiorouter.data.dao.PermissionGrantDao
import com.vyzorix.audiorouter.data.dao.RouteHistoryDao
import com.vyzorix.audiorouter.data.dao.UpdateStateDao
import com.vyzorix.audiorouter.data.entity.CrashEvent
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import com.vyzorix.audiorouter.data.entity.PermissionGrantRecord
import com.vyzorix.audiorouter.data.entity.RouteHistoryEntry
import com.vyzorix.audiorouter.data.entity.UpdateRecord

/**
 * Encrypted Room database for the daemon. SQLCipher full-DB encryption per
 * ADR-0004; passphrase is held by [com.vyzorix.audiorouter.common.utils.CryptoHelper].
 *
 * Schema is exported under `core/data/schemas/` (KSP arg `room.schemaLocation`)
 * so every migration shows up as a reviewable file in PRs.
 *
 * Version history
 * ---------------
 *  - v1 (PR #7): `daemon_state`, `crash_events`, `update_state` only;
 *    enum columns stored as raw `String` (no `TypeConverter`).
 *  - v2 (PR #8): adds `route_history` + `permission_grants`; converts
 *    every enum column to typed converters (no on-disk format change,
 *    but the Room-generated reader code changes, hence the migration).
 */
@Database(
    entities = [
        DaemonStateSnapshot::class,
        CrashEvent::class,
        UpdateRecord::class,
        RouteHistoryEntry::class,
        PermissionGrantRecord::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(
    DateTimeTypeConverters::class,
    DaemonStateTypeConverters::class,
    CrashEventTypeConverters::class,
    UpdateStateTypeConverters::class,
    AudioRouteTypeConverters::class,
    PermissionGrantTypeConverters::class,
)
public abstract class AppDatabase : RoomDatabase() {

    public abstract fun daemonStateDao(): DaemonStateDao
    public abstract fun crashEventDao(): CrashEventDao
    public abstract fun updateStateDao(): UpdateStateDao
    public abstract fun routeHistoryDao(): RouteHistoryDao
    public abstract fun permissionGrantDao(): PermissionGrantDao

    public companion object {
        public const val DATABASE_NAME: String = "vyzorix_audiorouter.db"
        public const val VERSION: Int = 2
    }
}

package com.vyzorix.audiorouter.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [AppDatabase].
 *
 * Migration JSON files live in `core/data/schemas/com.vyzorix.audiorouter.data.database.AppDatabase/`
 * and are committed alongside the code that produces them. Reviewing the
 * JSON diff is the canonical sanity check for schema bumps.
 */
public object AppDatabaseMigrations {

    /**
     * v1 → v2:
     *   - Adds `route_history` (Layer 3 audit log).
     *   - Adds `permission_grants` (Layer 6 audit log).
     *   - No data migration is required for existing tables — enum columns
     *     were already stored as their `name` strings in v1, and the v2
     *     `TypeConverter`s read/write the same on-disk format.
     */
    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `route_history` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `transitionEpochMs` INTEGER NOT NULL,
                    `fromRoute` TEXT NOT NULL,
                    `toRoute` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `audioDeviceId` INTEGER,
                    `originMarker` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_route_history_transitionEpochMs` ON `route_history` (`transitionEpochMs`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_route_history_fromRoute_toRoute` ON `route_history` (`fromRoute`, `toRoute`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `permission_grants` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `recordedAtEpochMs` INTEGER NOT NULL,
                    `permission` TEXT NOT NULL,
                    `outcome` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `automationAttemptId` INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_permission_grants_recordedAtEpochMs` ON `permission_grants` (`recordedAtEpochMs`)",
            )

            // The crash_events.epochMs and daemon_state.snapshotEpochMs
            // indices were declared on the entities in v1 but never
            // backfilled into the migration. Re-asserting CREATE INDEX IF
            // NOT EXISTS here is safe (no-op when Room already created
            // them) and gives us a single source of truth for the v2
            // baseline.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crash_events_epochMs` ON `crash_events` (`epochMs`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_daemon_state_snapshotEpochMs` ON `daemon_state` (`snapshotEpochMs`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_update_state_checkedAtEpochMs` ON `update_state` (`checkedAtEpochMs`)",
            )
        }
    }

    /**
     * Full migration set passed to [androidx.room.RoomDatabase.Builder.addMigrations].
     * Each entry must move forward by exactly one version so the migration
     * graph stays trivially auditable.
     */
    public val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}

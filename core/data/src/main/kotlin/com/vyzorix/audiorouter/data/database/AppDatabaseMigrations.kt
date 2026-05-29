package com.vyzorix.audiorouter.data.database

import androidx.room.migration.Migration

/**
 * Schema migrations for [AppDatabase]. Empty in Layer 1 — version 1 is the
 * baseline. Each future schema bump MUST land its `Migration` object here
 * and its exported JSON in `core/data/schemas/com.vyzorix.audiorouter.data.database.AppDatabase/`.
 */
public object AppDatabaseMigrations {
    public val ALL: Array<Migration> = emptyArray()
}

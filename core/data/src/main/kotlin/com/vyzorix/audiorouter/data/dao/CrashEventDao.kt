package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.data.entity.CrashEventEntity

/**
 * DAO for `crash_events` rows. See doc/SYSTEM_MAP.md §5.1 and doc/DOC_4
 * for the recovery ladder that drives these reads.
 */
@Dao
public interface CrashEventDao {

    @Insert
    public suspend fun insert(entity: CrashEventEntity): Long

    @Query("SELECT * FROM crash_events ORDER BY epochMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<CrashEventEntity>

    @Query("SELECT COUNT(*) FROM crash_events WHERE epochMs >= :sinceEpochMs")
    public suspend fun countSince(sinceEpochMs: Long): Int

    @Query("DELETE FROM crash_events WHERE epochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int
}

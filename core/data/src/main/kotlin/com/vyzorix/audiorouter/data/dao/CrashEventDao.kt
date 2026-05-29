package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.data.entity.CrashEvent
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `crash_events` rows. See `doc/SYSTEM_MAP.md` §5.1 and `doc/DOC_4`
 * for the recovery ladder that drives these reads.
 *
 * Per `doc/BUILD_ORDER.md` Layer 1: "Any DAO method whose call site is in
 * a later layer can stay unused; do not delete the DAO method just because
 * nothing calls it yet."
 */
@Dao
public interface CrashEventDao {

    @Insert
    public suspend fun insert(entity: CrashEvent): Long

    @Query("SELECT * FROM crash_events ORDER BY epochMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<CrashEvent>

    @Query("SELECT * FROM crash_events ORDER BY epochMs DESC LIMIT :limit")
    public fun observeRecent(limit: Int): Flow<List<CrashEvent>>

    @Query("SELECT COUNT(*) FROM crash_events WHERE epochMs >= :sinceEpochMs")
    public suspend fun countSince(sinceEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM crash_events WHERE crashType = :crashType AND epochMs >= :sinceEpochMs")
    public suspend fun countOfTypeSince(crashType: CrashType, sinceEpochMs: Long): Int

    @Query("DELETE FROM crash_events WHERE epochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int

    @Query("DELETE FROM crash_events")
    public suspend fun deleteAll(): Int
}

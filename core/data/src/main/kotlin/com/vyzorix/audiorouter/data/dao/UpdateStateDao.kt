package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.common.enums.UpdateState
import com.vyzorix.audiorouter.data.entity.UpdateRecord
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `update_state` table. See `doc/SYSTEM_MAP.md` §8.4 (update
 * state machine) and `doc/DOC_8` (OTA delivery).
 */
@Dao
public interface UpdateStateDao {

    @Insert
    public suspend fun insert(entity: UpdateRecord): Long

    @Query("SELECT * FROM update_state ORDER BY checkedAtEpochMs DESC LIMIT 1")
    public suspend fun latest(): UpdateRecord?

    @Query("SELECT * FROM update_state ORDER BY checkedAtEpochMs DESC LIMIT 1")
    public fun observeLatest(): Flow<UpdateRecord?>

    @Query("SELECT * FROM update_state ORDER BY checkedAtEpochMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<UpdateRecord>

    @Query("SELECT * FROM update_state WHERE updateState = :state ORDER BY checkedAtEpochMs DESC LIMIT :limit")
    public suspend fun recentByState(state: UpdateState, limit: Int): List<UpdateRecord>

    @Query("DELETE FROM update_state WHERE checkedAtEpochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int

    @Query("DELETE FROM update_state")
    public suspend fun deleteAll(): Int
}

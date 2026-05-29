package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.data.entity.UpdateStateEntity

/**
 * DAO for `update_state` rows. See doc/SYSTEM_MAP.md §8.4 (update state
 * machine) and doc/DOC_8 (OTA delivery).
 */
@Dao
public interface UpdateStateDao {

    @Insert
    public suspend fun insert(entity: UpdateStateEntity): Long

    @Query("SELECT * FROM update_state ORDER BY checkedAtEpochMs DESC LIMIT 1")
    public suspend fun latest(): UpdateStateEntity?

    @Query("SELECT * FROM update_state ORDER BY checkedAtEpochMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<UpdateStateEntity>

    @Query("DELETE FROM update_state WHERE checkedAtEpochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int
}

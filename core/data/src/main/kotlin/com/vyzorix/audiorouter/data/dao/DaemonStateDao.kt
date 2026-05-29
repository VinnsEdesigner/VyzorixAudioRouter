package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `daemon_state` table. See `doc/SYSTEM_MAP.md` §6.1 for the
 * Room thread model — every call here MUST execute on `AppDispatchers.IO`.
 */
@Dao
public interface DaemonStateDao {

    @Insert
    public suspend fun insert(entity: DaemonStateSnapshot): Long

    @Query("SELECT * FROM daemon_state ORDER BY snapshotEpochMs DESC LIMIT 1")
    public suspend fun latest(): DaemonStateSnapshot?

    @Query("SELECT * FROM daemon_state ORDER BY snapshotEpochMs DESC LIMIT 1")
    public fun observeLatest(): Flow<DaemonStateSnapshot?>

    @Query("SELECT * FROM daemon_state WHERE snapshotEpochMs >= :sinceEpochMs ORDER BY snapshotEpochMs DESC")
    public suspend fun since(sinceEpochMs: Long): List<DaemonStateSnapshot>

    @Query("DELETE FROM daemon_state WHERE snapshotEpochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int

    @Query("DELETE FROM daemon_state")
    public suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM daemon_state")
    public suspend fun count(): Int
}

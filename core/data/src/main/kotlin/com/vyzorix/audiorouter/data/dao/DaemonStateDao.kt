package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.data.entity.DaemonStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `daemon_state` rows. See doc/SYSTEM_MAP.md §6.1 for the Room thread
 * model — every call here MUST execute on `AppDispatchers.IO`.
 *
 * Methods used only by later layers are kept here per BUILD_ORDER.md
 * Layer 1: "Any DAO method whose call site is in a later layer can stay
 * unused; do not delete the DAO method just because nothing calls it yet."
 */
@Dao
public interface DaemonStateDao {

    @Insert
    public suspend fun insert(entity: DaemonStateEntity): Long

    @Query("SELECT * FROM daemon_state ORDER BY snapshotEpochMs DESC LIMIT 1")
    public suspend fun latest(): DaemonStateEntity?

    @Query("SELECT * FROM daemon_state ORDER BY snapshotEpochMs DESC LIMIT 1")
    public fun observeLatest(): Flow<DaemonStateEntity?>

    @Query("DELETE FROM daemon_state WHERE snapshotEpochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM daemon_state")
    public suspend fun count(): Int
}

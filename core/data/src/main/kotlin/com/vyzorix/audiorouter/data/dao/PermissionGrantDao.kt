package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.data.entity.PermissionGrantRecord
import com.vyzorix.audiorouter.data.entity.PermissionOutcome
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `permission_grants` rows. Written by Layer 6's
 * `PermissionGrantTracker`, consumed by Layer 7 recovery and Layer 8 C2
 * telemetry.
 */
@Dao
public interface PermissionGrantDao {

    @Insert
    public suspend fun insert(record: PermissionGrantRecord): Long

    @Query("SELECT * FROM permission_grants ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<PermissionGrantRecord>

    @Query(
        """
        SELECT * FROM permission_grants
        WHERE permission = :permission
        ORDER BY recordedAtEpochMs DESC LIMIT :limit
        """,
    )
    public suspend fun recentForPermission(permission: String, limit: Int): List<PermissionGrantRecord>

    @Query(
        """
        SELECT * FROM permission_grants
        WHERE permission = :permission
        ORDER BY recordedAtEpochMs DESC LIMIT 1
        """,
    )
    public suspend fun latestForPermission(permission: String): PermissionGrantRecord?

    @Query(
        """
        SELECT * FROM permission_grants
        WHERE permission = :permission
        ORDER BY recordedAtEpochMs DESC LIMIT 1
        """,
    )
    public fun observeLatestForPermission(permission: String): Flow<PermissionGrantRecord?>

    @Query(
        """
        SELECT COUNT(*) FROM permission_grants
        WHERE permission = :permission AND outcome = :outcome AND recordedAtEpochMs >= :sinceEpochMs
        """,
    )
    public suspend fun countOutcomesSince(permission: String, outcome: PermissionOutcome, sinceEpochMs: Long): Int

    @Query("DELETE FROM permission_grants WHERE recordedAtEpochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int

    @Query("DELETE FROM permission_grants")
    public suspend fun deleteAll(): Int
}

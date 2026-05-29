package com.vyzorix.audiorouter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.RouteHistoryEntry
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `route_history` rows. Written by Layer 3's
 * `RouteHistoryRecorder`, consumed by Layer 6's `RouteForensicsReporter`
 * (when a crash bundle is being assembled) and by Layer 7's
 * `RouteDriftDetector`.
 *
 * The query set intentionally covers the full Layer 3+ surface so future
 * layers don't need to come back to add methods. Methods without a Layer 1
 * caller are listed per `doc/BUILD_ORDER.md` Layer 1: "Any DAO method
 * whose call site is in a later layer can stay unused".
 */
@Dao
public interface RouteHistoryDao {

    @Insert
    public suspend fun insert(entry: RouteHistoryEntry): Long

    @Query("SELECT * FROM route_history ORDER BY transitionEpochMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<RouteHistoryEntry>

    @Query("SELECT * FROM route_history ORDER BY transitionEpochMs DESC LIMIT :limit")
    public fun observeRecent(limit: Int): Flow<List<RouteHistoryEntry>>

    @Query("SELECT * FROM route_history WHERE transitionEpochMs >= :sinceEpochMs ORDER BY transitionEpochMs ASC")
    public suspend fun since(sinceEpochMs: Long): List<RouteHistoryEntry>

    @Query(
        """
        SELECT COUNT(*) FROM route_history
        WHERE reason = :reason AND transitionEpochMs >= :sinceEpochMs
        """,
    )
    public suspend fun countOfReasonSince(reason: RouteTransitionReason, sinceEpochMs: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM route_history
        WHERE fromRoute = :fromRoute AND toRoute = :toRoute AND transitionEpochMs >= :sinceEpochMs
        """,
    )
    public suspend fun countTransitionsSince(
        fromRoute: AudioRouteKind,
        toRoute: AudioRouteKind,
        sinceEpochMs: Long,
    ): Int

    @Query("DELETE FROM route_history WHERE transitionEpochMs < :olderThanEpochMs")
    public suspend fun deleteOlderThan(olderThanEpochMs: Long): Int

    @Query("DELETE FROM route_history")
    public suspend fun deleteAll(): Int
}

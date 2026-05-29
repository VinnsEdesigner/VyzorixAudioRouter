package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.dao.RouteHistoryDao
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.RouteHistoryEntry
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason
import kotlinx.coroutines.flow.Flow

/**
 * Repository wrapper around [RouteHistoryDao]. Layer 3+ recorders go
 * through this class so the persistence shape can evolve independently of
 * the service-side recorder API.
 */
public class RouteHistoryRepository(
    private val dao: RouteHistoryDao,
) {
    public suspend fun record(entry: RouteHistoryEntry): Long = dao.insert(entry)
    public suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<RouteHistoryEntry> = dao.recent(limit)
    public fun observeRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<RouteHistoryEntry>> = dao.observeRecent(limit)
    public suspend fun since(sinceEpochMs: Long): List<RouteHistoryEntry> = dao.since(sinceEpochMs)
    public suspend fun countOfReasonSince(reason: RouteTransitionReason, sinceEpochMs: Long): Int =
        dao.countOfReasonSince(reason, sinceEpochMs)
    public suspend fun countTransitionsSince(
        fromRoute: AudioRouteKind,
        toRoute: AudioRouteKind,
        sinceEpochMs: Long,
    ): Int = dao.countTransitionsSince(fromRoute, toRoute, sinceEpochMs)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)
    public suspend fun clear(): Int = dao.deleteAll()

    public companion object {
        public const val DEFAULT_RECENT_LIMIT: Int = 64
    }
}

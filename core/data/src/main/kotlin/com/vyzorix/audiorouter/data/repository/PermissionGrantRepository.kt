package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.dao.PermissionGrantDao
import com.vyzorix.audiorouter.data.entity.PermissionGrantRecord
import com.vyzorix.audiorouter.data.entity.PermissionOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Repository wrapper around [PermissionGrantDao]. Listed in
 * `doc/VyzorixAudioRouter_RepoTree.md` §core/data/repository/ alongside
 * the DAO so Layer 6+ recovery code has a stable API surface.
 */
public class PermissionGrantRepository(
    private val dao: PermissionGrantDao,
) {
    public suspend fun record(record: PermissionGrantRecord): Long = dao.insert(record)
    public suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<PermissionGrantRecord> = dao.recent(limit)
    public suspend fun recentForPermission(permission: String, limit: Int = DEFAULT_RECENT_LIMIT): List<PermissionGrantRecord> =
        dao.recentForPermission(permission, limit)
    public suspend fun latestForPermission(permission: String): PermissionGrantRecord? =
        dao.latestForPermission(permission)
    public fun observeLatestForPermission(permission: String): Flow<PermissionGrantRecord?> =
        dao.observeLatestForPermission(permission)
    public suspend fun countOutcomesSince(permission: String, outcome: PermissionOutcome, sinceEpochMs: Long): Int =
        dao.countOutcomesSince(permission, outcome, sinceEpochMs)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)
    public suspend fun clear(): Int = dao.deleteAll()

    public companion object {
        public const val DEFAULT_RECENT_LIMIT: Int = 32
    }
}

package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.dao.DaemonStateDao
import com.vyzorix.audiorouter.data.entity.DaemonStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository wrapper around [DaemonStateDao]. Layer 5+ consumers go through
 * this class (not the DAO directly) so the DAO contract can evolve without
 * breaking service callers.
 *
 * Kept intentionally thin — Layer 1 only needs CRUD passthrough.
 */
public class DaemonStateRepository(
    private val dao: DaemonStateDao,
) {
    public suspend fun record(entity: DaemonStateEntity): Long = dao.insert(entity)
    public suspend fun latest(): DaemonStateEntity? = dao.latest()
    public fun observeLatest(): Flow<DaemonStateEntity?> = dao.observeLatest()
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)
    public suspend fun count(): Int = dao.count()
}

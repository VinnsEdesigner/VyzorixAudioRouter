package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.dao.DaemonStateDao
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Repository wrapper around [DaemonStateDao]. Layer 5+ consumers go through
 * this class (not the DAO directly) so the DAO contract can evolve without
 * breaking service callers.
 */
public class DaemonStateRepository(
    private val dao: DaemonStateDao,
) {
    public suspend fun record(snapshot: DaemonStateSnapshot): Long = dao.insert(snapshot)
    public suspend fun latest(): DaemonStateSnapshot? = dao.latest()
    public fun observeLatest(): Flow<DaemonStateSnapshot?> = dao.observeLatest()
    public suspend fun since(sinceEpochMs: Long): List<DaemonStateSnapshot> = dao.since(sinceEpochMs)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)
    public suspend fun clear(): Int = dao.deleteAll()
    public suspend fun count(): Int = dao.count()
}

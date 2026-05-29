package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.data.dao.CrashEventDao
import com.vyzorix.audiorouter.data.entity.CrashEvent
import kotlinx.coroutines.flow.Flow

/** Repository wrapper around [CrashEventDao]. See `doc/DOC_4` + `doc/DOC_5`. */
public class CrashEventRepository(
    private val dao: CrashEventDao,
) {
    public suspend fun record(entity: CrashEvent): Long = dao.insert(entity)
    public suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<CrashEvent> = dao.recent(limit)
    public fun observeRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<CrashEvent>> = dao.observeRecent(limit)
    public suspend fun countSince(sinceEpochMs: Long): Int = dao.countSince(sinceEpochMs)
    public suspend fun countOfTypeSince(crashType: CrashType, sinceEpochMs: Long): Int =
        dao.countOfTypeSince(crashType, sinceEpochMs)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)
    public suspend fun clear(): Int = dao.deleteAll()

    public companion object {
        public const val DEFAULT_RECENT_LIMIT: Int = 32
    }
}

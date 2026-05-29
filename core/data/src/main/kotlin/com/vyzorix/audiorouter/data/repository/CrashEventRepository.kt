package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.dao.CrashEventDao
import com.vyzorix.audiorouter.data.entity.CrashEventEntity

/** Repository wrapper around [CrashEventDao]. See doc/DOC_4 + doc/DOC_5. */
public class CrashEventRepository(
    private val dao: CrashEventDao,
) {
    public suspend fun record(entity: CrashEventEntity): Long = dao.insert(entity)
    public suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<CrashEventEntity> = dao.recent(limit)
    public suspend fun countSince(sinceEpochMs: Long): Int = dao.countSince(sinceEpochMs)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)

    public companion object {
        public const val DEFAULT_RECENT_LIMIT: Int = 32
    }
}

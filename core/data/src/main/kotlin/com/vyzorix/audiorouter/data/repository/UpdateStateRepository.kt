package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.dao.UpdateStateDao
import com.vyzorix.audiorouter.data.entity.UpdateStateEntity

/** Repository wrapper around [UpdateStateDao]. See doc/SYSTEM_MAP.md §8.4. */
public class UpdateStateRepository(
    private val dao: UpdateStateDao,
) {
    public suspend fun record(entity: UpdateStateEntity): Long = dao.insert(entity)
    public suspend fun latest(): UpdateStateEntity? = dao.latest()
    public suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<UpdateStateEntity> = dao.recent(limit)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)

    public companion object {
        public const val DEFAULT_RECENT_LIMIT: Int = 8
    }
}

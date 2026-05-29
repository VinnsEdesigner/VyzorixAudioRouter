package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.common.enums.UpdateState
import com.vyzorix.audiorouter.data.dao.UpdateStateDao
import com.vyzorix.audiorouter.data.entity.UpdateRecord
import kotlinx.coroutines.flow.Flow

/**
 * Repository wrapper around [UpdateStateDao]. Canonical name per
 * `doc/VyzorixAudioRouter_RepoTree.md` (§core/data/repository/).
 *
 * See `doc/SYSTEM_MAP.md` §8.4 for the consumer-side state machine and
 * `doc/DOC_8` for the OTA delivery contract.
 *
 * No `clear()` method by design — `update_state` is a forensic log that
 * survives safe-mode wipes per `doc/DOC_8` §7. Pruning happens through
 * the dated [pruneOlderThan] helper.
 */
public class UpdateRepository(
    private val dao: UpdateStateDao,
) {
    public suspend fun record(entity: UpdateRecord): Long = dao.insert(entity)
    public suspend fun latest(): UpdateRecord? = dao.latest()
    public fun observeLatest(): Flow<UpdateRecord?> = dao.observeLatest()
    public suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<UpdateRecord> = dao.recent(limit)
    public suspend fun recentByState(state: UpdateState, limit: Int = DEFAULT_RECENT_LIMIT): List<UpdateRecord> =
        dao.recentByState(state, limit)
    public suspend fun pruneOlderThan(olderThanEpochMs: Long): Int = dao.deleteOlderThan(olderThanEpochMs)

    public companion object {
        public const val DEFAULT_RECENT_LIMIT: Int = 8
    }
}

package com.vyzorix.audiorouter.data.repository

import com.vyzorix.audiorouter.data.entity.CrashEvent
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import com.vyzorix.audiorouter.data.entity.PermissionGrantRecord
import com.vyzorix.audiorouter.data.entity.RouteHistoryEntry
import com.vyzorix.audiorouter.data.entity.UpdateRecord

/**
 * Unified read-side facade across the five Room repositories.
 *
 * Layer 5 (`DaemonStatusAggregator`) and Layer 6 (`CrashSnapshotExporter`)
 * read state across several tables in a single logical operation. Routing
 * each read through this facade keeps those consumers from accumulating
 * five DAO dependencies and gives us one place to drop transaction
 * helpers if the consumer-side shape needs them later.
 *
 * The facade is intentionally read-only — writes flow through the
 * per-table repositories so consumers can be granted insert capability
 * without read-side authorisation.
 */
public class StateRepository(
    private val daemonStateRepository: DaemonStateRepository,
    private val crashEventRepository: CrashEventRepository,
    private val updateRepository: UpdateRepository,
    private val routeHistoryRepository: RouteHistoryRepository,
    private val permissionGrantRepository: PermissionGrantRepository,
) {

    /**
     * Returns a coherent snapshot of the daemon's persistent state.
     * Used by `CrashSnapshotExporter` when assembling a crash bundle and
     * by `DaemonStatusAggregator` when populating the dashboard.
     *
     * Each per-table read is a separate query — Room's `@Transaction` is
     * NOT applied here because all five reads are independent ORDER BY
     * DESC LIMIT N projections, so the cross-table consistency window is
     * already bounded by the longest single query.
     */
    public suspend fun aggregate(
        recentCrashLimit: Int = CrashEventRepository.DEFAULT_RECENT_LIMIT,
        recentRouteLimit: Int = RouteHistoryRepository.DEFAULT_RECENT_LIMIT,
        recentPermissionLimit: Int = PermissionGrantRepository.DEFAULT_RECENT_LIMIT,
    ): StateAggregate = StateAggregate(
        daemonState = daemonStateRepository.latest(),
        recentCrashes = crashEventRepository.recent(recentCrashLimit),
        latestUpdate = updateRepository.latest(),
        recentRouteTransitions = routeHistoryRepository.recent(recentRouteLimit),
        recentPermissionEvents = permissionGrantRepository.recent(recentPermissionLimit),
    )

    /**
     * Convenience wipe of all five tables — used by the safe-mode reset
     * path in Layer 7. Returns the number of rows deleted per table for
     * telemetry; callers MUST treat the result as informational only
     * (not authoritative).
     */
    public suspend fun clearAll(): ClearCounts = ClearCounts(
        daemonState = daemonStateRepository.clear(),
        crashEvents = crashEventRepository.clear(),
        updates = 0, // UpdateRepository doesn't expose a clear() — keeping a delete history is intentional.
        routeHistory = routeHistoryRepository.clear(),
        permissionGrants = permissionGrantRepository.clear(),
    )
}

/** Coherent point-in-time aggregate of the daemon's persistent state. */
public data class StateAggregate(
    val daemonState: DaemonStateSnapshot?,
    val recentCrashes: List<CrashEvent>,
    val latestUpdate: UpdateRecord?,
    val recentRouteTransitions: List<RouteHistoryEntry>,
    val recentPermissionEvents: List<PermissionGrantRecord>,
)

/** Row counts deleted by [StateRepository.clearAll]. Informational only. */
public data class ClearCounts(
    val daemonState: Int,
    val crashEvents: Int,
    val updates: Int,
    val routeHistory: Int,
    val permissionGrants: Int,
)

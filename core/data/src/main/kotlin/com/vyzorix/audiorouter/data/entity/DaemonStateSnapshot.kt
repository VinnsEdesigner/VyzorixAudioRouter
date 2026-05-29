package com.vyzorix.audiorouter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState

/**
 * Persistent snapshot of the daemon's lifecycle state.
 *
 * Layer 5 (notification dashboard) reads the most recent row; Layer 6
 * (crash forensics) joins against [snapshotEpochMs] when correlating
 * runtime telemetry with [CrashEvent] rows.
 *
 * Indexed on [snapshotEpochMs] DESC because every read query in
 * `doc/SYSTEM_MAP.md` §6.1 is "the latest snapshot" or "snapshots in the
 * last N minutes" — both of which benefit from the index.
 */
@Entity(
    tableName = "daemon_state",
    indices = [Index(value = ["snapshotEpochMs"])],
)
public data class DaemonStateSnapshot(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "snapshotEpochMs")
    val snapshotEpochMs: Long,

    /** Persisted via `DaemonStateTypeConverters`; see column docs there. */
    @ColumnInfo(name = "daemonState")
    val daemonState: DaemonState,

    @ColumnInfo(name = "routeState")
    val routeState: RouteState,

    @ColumnInfo(name = "captureState")
    val captureState: CaptureState,

    @ColumnInfo(name = "safeModeEnabled")
    val safeModeEnabled: Boolean,
)

package com.vyzorix.audiorouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent snapshot of the daemon's lifecycle state. Layer 5 (notification
 * dashboard) reads the most recent row; Layer 6 (crash forensics) joins
 * against [snapshotEpochMs] when correlating runtime telemetry with
 * `CrashEventEntity` rows.
 *
 * The set of fields here is deliberately narrow: the on-disk layout for
 * Layer 1 only carries what `DaemonStatusAggregator` produces today.
 * Additional fields land in later layers behind Room migrations.
 */
@Entity(tableName = "daemon_state")
public data class DaemonStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotEpochMs: Long,
    /** Enum name from `com.vyzorix.audiorouter.common.enums.DaemonState`. */
    val daemonState: String,
    /** Enum name from `com.vyzorix.audiorouter.common.enums.RouteState`. */
    val routeState: String,
    /** Enum name from `com.vyzorix.audiorouter.common.enums.CaptureState`. */
    val captureState: String,
    val safeModeEnabled: Boolean,
)

package com.vyzorix.audiorouter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vyzorix.audiorouter.common.enums.UpdateState

/**
 * Persistent record of an OTA update transaction. Layer 7
 * (`UpdateStateMachine`) writes a new row when a check / download / install
 * transitions state (per the state machine in `doc/SYSTEM_MAP.md` §8.4).
 *
 * Multi-row by design — the daemon keeps a short window of history so
 * `UpdateRecoveryCoordinator` can spot install-loop patterns.
 */
@Entity(
    tableName = "update_state",
    indices = [Index(value = ["checkedAtEpochMs"])],
)
public data class UpdateRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "checkedAtEpochMs")
    val checkedAtEpochMs: Long,

    /** Persisted via `UpdateStateTypeConverters.fromUpdateState`. */
    @ColumnInfo(name = "updateState")
    val updateState: UpdateState,

    @ColumnInfo(name = "availableVersionCode")
    val availableVersionCode: Long?,

    @ColumnInfo(name = "availableVersionName")
    val availableVersionName: String?,

    @ColumnInfo(name = "downloadedPath")
    val downloadedPath: String?,

    @ColumnInfo(name = "checksumHex")
    val checksumHex: String?,
)

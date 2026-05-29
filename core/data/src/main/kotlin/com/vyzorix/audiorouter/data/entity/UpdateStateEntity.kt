package com.vyzorix.audiorouter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent record of the most recent OTA update transaction. Layer 7
 * writes a new row when a check / download / install state transitions
 * (per the state machine in doc/SYSTEM_MAP.md §8.4).
 */
@Entity(tableName = "update_state")
public data class UpdateStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checkedAtEpochMs: Long,
    /** Enum name from `com.vyzorix.audiorouter.common.enums.UpdateState`. */
    val updateState: String,
    val availableVersionCode: Long?,
    val availableVersionName: String?,
    val downloadedPath: String?,
    val checksumHex: String?,
)

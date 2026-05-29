package com.vyzorix.audiorouter.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single crash / soft-reboot record produced by `GlobalExceptionHandler` and
 * `SoftRebootTracker` (Layer 6). Indexed by epoch so the recovery ladder in
 * doc/DOC_4 can window-scan recent crashes without a full table scan on the
 * Nokia C22's slow eMMC.
 */
@Entity(
    tableName = "crash_events",
    indices = [Index(value = ["epochMs"])],
)
public data class CrashEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    /** Enum name from `com.vyzorix.audiorouter.common.enums.CrashType`. */
    val crashType: String,
    /** Lower-cased fully-qualified throwable class name (or "soft_reboot"). */
    val signature: String,
    /** Truncated stack trace (first 4 kB) — full traces go to `RollingLogWriter`. */
    val stackHead: String,
    val processUptimeMs: Long,
    val consecutiveCrashes: Int,
)

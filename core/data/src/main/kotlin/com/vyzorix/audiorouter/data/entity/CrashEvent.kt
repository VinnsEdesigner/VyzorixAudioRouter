package com.vyzorix.audiorouter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vyzorix.audiorouter.common.enums.CrashType

/**
 * A single crash / soft-reboot record produced by `GlobalExceptionHandler`
 * and `SoftRebootTracker` (Layer 6).
 *
 * Indexed by [epochMs] so the recovery ladder in `doc/DOC_4` can window-scan
 * recent crashes without a full table scan on the Nokia C22's slow eMMC.
 *
 * The on-disk table name `crash_events` is preserved across the v1 → v2
 * rename of this class (the rename is pure code; SQLite layout is identical).
 */
@Entity(
    tableName = "crash_events",
    indices = [Index(value = ["epochMs"])],
)
public data class CrashEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Unix-millis the crash was recorded. Indexed for window queries. */
    @ColumnInfo(name = "epochMs")
    val epochMs: Long,

    /**
     * Crash classification. Persisted as the enum's `name` string via
     * `CrashEventTypeConverters.fromCrashType` so the column stays readable
     * in `sqlcipher-shell` dumps and survives enum reordering.
     */
    @ColumnInfo(name = "crashType")
    val crashType: CrashType,

    /**
     * Lower-cased fully-qualified throwable class name, or `"soft_reboot"`
     * for crashes detected via uptime forensics. Used by
     * `CrashSignatureBuilder` (Layer 6) to group similar crashes for the
     * recovery ladder.
     */
    @ColumnInfo(name = "signature")
    val signature: String,

    /**
     * First 4 kB of the stack trace. Full traces go to `RollingLogWriter`
     * (Layer 5) — this column is for the recovery-ladder heuristic only.
     */
    @ColumnInfo(name = "stackHead")
    val stackHead: String,

    /** Wall-clock milliseconds the process had been up before the crash. */
    @ColumnInfo(name = "processUptimeMs")
    val processUptimeMs: Long,

    /**
     * Number of crashes the recovery ladder has seen in the current
     * back-off window. Drives the Layer 6 transition from `ELEVATED` →
     * `HIGH` → `CRITICAL` risk levels.
     */
    @ColumnInfo(name = "consecutiveCrashes")
    val consecutiveCrashes: Int,
)

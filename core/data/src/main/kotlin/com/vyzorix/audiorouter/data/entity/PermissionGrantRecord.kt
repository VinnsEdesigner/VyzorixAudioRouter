package com.vyzorix.audiorouter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit row for every permission grant / revoke / denial the daemon
 * observes. Written by Layer 6's `PermissionGrantTracker` (UI automation +
 * package-manager broadcast taps), consumed by Layer 7's recovery code
 * to diagnose silent revocations.
 *
 * Indexed on [recordedAtEpochMs] for the typical "what changed in the
 * last hour?" query.
 */
@Entity(
    tableName = "permission_grants",
    indices = [Index(value = ["recordedAtEpochMs"])],
)
public data class PermissionGrantRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "recordedAtEpochMs")
    val recordedAtEpochMs: Long,

    /**
     * Fully-qualified Android permission string (e.g.
     * `android.permission.RECORD_AUDIO`) or one of the synthetic
     * Vyzorix-specific markers (see [VYZORIX_PERMISSION_PREFIX]).
     */
    @ColumnInfo(name = "permission")
    val permission: String,

    /**
     * Outcome the daemon recorded for this permission at this moment.
     * Persisted via `PermissionGrantTypeConverters` (lives alongside this
     * file is in Layer 1; converter is added in the same `@TypeConverters`
     * block on `AppDatabase`).
     */
    @ColumnInfo(name = "outcome")
    val outcome: PermissionOutcome,

    /**
     * Free-form context — typically `"runtime_dialog"`,
     * `"settings_screen"`, `"system_broadcast"`, or an automation
     * call-site id. Capped to 64 chars by the tracker.
     */
    @ColumnInfo(name = "source")
    val source: String,

    /**
     * Optional automation-attempt id; non-null when the grant was
     * delivered via `AutomationOrchestrator` so the recovery code can
     * cross-reference against the `AutomationLog` (Layer 6).
     */
    @ColumnInfo(name = "automationAttemptId")
    val automationAttemptId: Long?,
) {
    public companion object {
        /** Prefix for Vyzorix-private "permissions" (e.g. accessibility-service-bound state). */
        public const val VYZORIX_PERMISSION_PREFIX: String = "vyzorix.synthetic."
    }
}

/**
 * Tri-state permission outcome.
 *
 *  - [GRANTED]: permission is currently granted to the daemon.
 *  - [DENIED]: permission was actively denied (user tapped Deny, or
 *    `checkSelfPermission` returned `PERMISSION_DENIED` after a request).
 *  - [REVOKED]: permission was previously granted and has now been
 *    revoked (system broadcast, settings toggle, or runtime reset). The
 *    recovery code treats this distinctly from [DENIED] because it
 *    implies the user already trusted the daemon at some point.
 */
public enum class PermissionOutcome {
    GRANTED,
    DENIED,
    REVOKED,
}

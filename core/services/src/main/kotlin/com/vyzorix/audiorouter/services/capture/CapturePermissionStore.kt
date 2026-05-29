// CapturePermissionStore — services-layer abstraction over Layer 1's
// `ProjectionMetadataStore` plus the in-memory consent state.
//
// Two concerns:
//   1. The `MediaProjection` token itself is per-grant ephemera — Android
//      forbids reusing the projection `Intent`+`resultCode` across process
//      deaths. So tokens are NOT persisted; the trampoline re-acquires
//      them on cold boot. This class only persists DESCRIPTIVE metadata
//      (start epoch, capture format, trigger origin) via
//      `ProjectionMetadataStore`.
//   2. The CURRENT-PROCESS consent state (have we observed a grant in
//      this process lifetime? when did the grant happen? was it
//      revoked?) lives in `@Volatile` fields and is reset on process
//      death. Reading these is the fast path for `BootStateRestorer`
//      to decide whether to call into the trampoline.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.4.

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.data.datastore.ProjectionMetadataSnapshot
import com.vyzorix.audiorouter.data.datastore.ProjectionMetadataStore
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow

/** In-process consent snapshot. */
public data class CaptureConsentState(
    /** True iff this process has observed a successful MediaProjection grant. */
    public val granted: Boolean,
    /** Wall-clock epoch ms of the grant; null when [granted] is false. */
    public val grantedAtEpochMs: Long?,
    /** Wall-clock epoch ms of the most recent revoke; null if no revoke yet. */
    public val revokedAtEpochMs: Long?,
    /** Free-form origin label (e.g. "bootstrap", "auto_reacquire", "manual"). */
    public val triggerOrigin: String?,
)

/**
 * Process-scoped consent state + persistent metadata facade.
 *
 * Construction parameters are non-suspending so callers can build this
 * eagerly during service onCreate; the [recordGrant] / [recordRevoke] /
 * [snapshotPersisted] methods are suspending where I/O is involved.
 */
public class CapturePermissionStore(
    private val projectionMetadataStore: ProjectionMetadataStore,
) {

    private val state: AtomicReference<CaptureConsentState> = AtomicReference(
        CaptureConsentState(
            granted = false,
            grantedAtEpochMs = null,
            revokedAtEpochMs = null,
            triggerOrigin = null,
        ),
    )

    /** Current in-process consent state. */
    public fun currentState(): CaptureConsentState = state.get()

    /** Flow of last-known persisted start epoch (per [ProjectionMetadataStore]). */
    public val lastPersistedSessionStartEpochMs: Flow<Long?>
        get() = projectionMetadataStore.lastSessionStartEpochMs

    /**
     * Record that the user just granted MediaProjection. The token itself
     * is held by [ProjectionTokenManager] — this only records the descriptive
     * metadata.
     */
    public suspend fun recordGrant(
        grantedAtEpochMs: Long,
        sampleRateHz: Int,
        channelCount: Int,
        triggerOrigin: String,
    ) {
        state.set(
            CaptureConsentState(
                granted = true,
                grantedAtEpochMs = grantedAtEpochMs,
                revokedAtEpochMs = null,
                triggerOrigin = triggerOrigin,
            ),
        )
        try {
            projectionMetadataStore.recordSessionStart(
                startEpochMs = grantedAtEpochMs,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                triggerOrigin = triggerOrigin,
            )
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "capture.permission.persist_failed reason=grant origin=$triggerOrigin err=${t.javaClass.simpleName}",
            )
        }
    }

    /**
     * Record that the projection has been revoked (either by the user
     * stopping casting or by the system tearing down our session). The
     * in-memory state flips to `granted = false` immediately; the persisted
     * stop epoch is written best-effort.
     */
    public suspend fun recordRevoke(revokedAtEpochMs: Long) {
        state.set(
            state.get().copy(
                granted = false,
                revokedAtEpochMs = revokedAtEpochMs,
            ),
        )
        try {
            projectionMetadataStore.recordSessionStop(stopEpochMs = revokedAtEpochMs)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "capture.permission.persist_failed reason=revoke err=${t.javaClass.simpleName}",
            )
        }
    }

    /** Async snapshot of the persisted descriptive metadata. */
    public suspend fun snapshotPersisted(): ProjectionMetadataSnapshot =
        projectionMetadataStore.snapshot()

    /** Erase both in-memory and persisted state (used on Safe Mode entry). */
    public suspend fun clear() {
        state.set(
            CaptureConsentState(
                granted = false,
                grantedAtEpochMs = null,
                revokedAtEpochMs = null,
                triggerOrigin = null,
            ),
        )
        try {
            projectionMetadataStore.clear()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "capture.permission.persist_failed reason=clear err=${t.javaClass.simpleName}",
            )
        }
    }

    private companion object {
        const val TAG: String = "CapturePermissionStore"
    }
}

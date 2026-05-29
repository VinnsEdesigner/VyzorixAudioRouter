// DaemonStateRecorder — writes daemon lifecycle snapshots into the Room
// `daemon_state` table.
//
// Why this matters for Layer 3.5 (no-ADB acceptance gate):
//   - The user retrieves diagnostics via the zipped FileLogger bundle. Plain
//     log lines tell you *what happened* but not *the state of the daemon
//     when it happened*.
//   - Persisting DaemonState/RouteState/CaptureState into Room gives Layer 5
//     and the offline analyser (run on the user's phone via Files-by-Google
//     opening a zipped sqlite) a structured picture of the soak run.
//   - Even before Layer 5 lands, the user can extract the daemon database
//     via the standard backup path (and Layer 6 will add an explicit DB
//     bundle export action).
//
// The recorder is intentionally non-blocking: writes are dispatched on a
// supplied CoroutineScope so the SpeakerForceManager hot path is never
// blocked on a database insert.

package com.vyzorix.audiorouter.services.state

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.data.entity.DaemonStateSnapshot
import com.vyzorix.audiorouter.data.repository.DaemonStateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Best-effort recorder that converts in-process state transitions into
 * persisted [DaemonStateSnapshot] rows.
 */
public class DaemonStateRecorder(
    private val scope: CoroutineScope,
    private val repository: DaemonStateRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Persist a snapshot of the current daemon state. Production code is
     * fire-and-forget; tests pass [scope]'s dispatcher to make this
     * deterministic.
     */
    public fun record(
        daemonState: DaemonState,
        routeState: RouteState,
        captureState: CaptureState = CaptureState.IDLE,
        safeModeEnabled: Boolean = false,
    ) {
        val snapshot = DaemonStateSnapshot(
            snapshotEpochMs = now(),
            daemonState = daemonState,
            routeState = routeState,
            captureState = captureState,
            safeModeEnabled = safeModeEnabled,
        )
        scope.launch(dispatcher) {
            runCatching { repository.record(snapshot) }
        }
    }
}

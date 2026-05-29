// ProjectionTokenSignal — reports whether the daemon currently holds a
// valid MediaProjection token.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 627:
//     core/services/foreground/signals/ProjectionTokenSignal.kt
//       "Asks ProjectionTokenManager.isValid() and produces SignalValue".
//
// This signal source uses [ProjectionTokenManager.currentSnapshot] for
// the read. When the projection layer hasn't been fully built yet (the
// Layer 4 trampoline + token re-acquisition flow exists, but the
// dashboard ships before the Layer 4-final integration that wires the
// token manager into the foreground graph), the signal returns
// `SignalValue.unknown(...)` so the aggregator can render "—" rather
// than misleading "OK".
//
// Banding policy:
//   - tokenManager is null or never granted → UNKNOWN
//   - snapshot.isActive == true             → OK
//   - snapshot.isActive == false            → WARN (the daemon is
//                                              awaiting a re-grant flow;
//                                              CRIT would be the place
//                                              for "tried 5x, gave up").

package com.vyzorix.audiorouter.services.foreground.signals

import com.vyzorix.audiorouter.services.capture.ProjectionTokenManager
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference

/** Reads `ProjectionTokenManager`. */
public class ProjectionTokenSignal(
    private val tokenManagerProvider: () -> ProjectionTokenManager?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    /**
     * Convenience constructor for callers that have a stable reference
     * to a [ProjectionTokenManager] instance.
     */
    public constructor(tokenManager: ProjectionTokenManager) : this(
        tokenManagerProvider = {
            val captured = AtomicReference(tokenManager)
            captured.get()
        },
    )

    public override val id: String = "projection_token"

    public override fun current(): SignalValue {
        val manager = try {
            tokenManagerProvider()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "projection_token.provider_threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "projection token manager unavailable",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        if (manager == null) {
            return SignalValue.unknown(
                label = "projection manager not wired",
                details = "Layer 4 token manager not yet attached",
                readEpochMs = clock(),
            )
        }
        val snapshot = try {
            manager.currentSnapshot()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "projection_token.read_threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "projection token read failed",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        return if (snapshot.isActive) {
            SignalValue.ok(
                label = "projection token active",
                details = "origin=${snapshot.triggerOrigin} grantedAtMs=${snapshot.grantedAtEpochMs}",
                readEpochMs = clock(),
            )
        } else {
            SignalValue.warn(
                label = "projection token inactive",
                details = "origin=${snapshot.triggerOrigin}",
                readEpochMs = clock(),
            )
        }
    }

    public companion object {
        private const val TAG: String = "ProjectionTokenSignal"
    }
}

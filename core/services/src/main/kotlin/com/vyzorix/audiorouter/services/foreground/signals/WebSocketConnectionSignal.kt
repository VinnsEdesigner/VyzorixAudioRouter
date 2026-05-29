// WebSocketConnectionSignal — reports whether the daemon's WebSocket
// transport is connected.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 628:
//     core/services/foreground/signals/WebSocketConnectionSignal.kt
//       "Reads WebSocketClientManager.isConnected() each tick".
//
// Layer 5 ships before the WebSocket transport (Layer 8). The signal
// source emits `SignalValue.unknown` until the WebSocket manager is
// wired in. The contract is identical to [ProjectionTokenSignal] — a
// provider lambda lets the field be lazily populated when Layer 8
// lands without rewiring the aggregator.
//
// Banding policy:
//   - provider returns null → UNKNOWN
//   - manager.isConnected() == true → OK
//   - manager.isConnected() == false → WARN

package com.vyzorix.audiorouter.services.foreground.signals

import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Read-only handle on whatever Layer 8 ships as the WebSocket manager. */
public interface WebSocketConnectionProbe {
    public fun isConnected(): Boolean
}

/** Reads a WebSocket connection probe. */
public class WebSocketConnectionSignal(
    private val probeProvider: () -> WebSocketConnectionProbe?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    public override val id: String = "websocket_connection"

    public override fun current(): SignalValue {
        val probe = try {
            probeProvider()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "ws.provider_threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "websocket probe unavailable",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        if (probe == null) {
            return SignalValue.unknown(
                label = "websocket not wired",
                details = "Layer 8 transport not yet attached",
                readEpochMs = clock(),
            )
        }
        val connected = try {
            probe.isConnected()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "ws.read_threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "websocket read failed",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        return if (connected) {
            SignalValue.ok(label = "websocket connected", readEpochMs = clock())
        } else {
            SignalValue.warn(label = "websocket disconnected", readEpochMs = clock())
        }
    }

    public companion object {
        private const val TAG: String = "WebSocketConnectionSignal"
    }
}

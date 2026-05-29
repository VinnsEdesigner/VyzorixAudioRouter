package com.vyzorix.audiorouter.common.model

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.enums.RouteState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of daemon health produced by DaemonStatusAggregator (Layer C
 * of the three-layer health stack — ADR-0007). The single read-model that flows
 * through the system: dashboard, RecoveryCoordinator, telemetry, and remote
 * status endpoints all consume this one struct.
 */
@Serializable
public data class DaemonStatus(
    @SerialName("daemon_state") val daemonState: DaemonState,
    @SerialName("route_state") val routeState: RouteState,
    @SerialName("capture_state") val captureState: CaptureState,
    @SerialName("risk_level") val riskLevel: RiskLevel,
    @SerialName("uptime_ms") val uptimeMs: Long,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("thermal_c") val thermalC: Float,
    @SerialName("websocket_connected") val websocketConnected: Boolean,
    @SerialName("last_command_at") val lastCommandAtMs: Long? = null,
    @SerialName("notes") val notes: List<String> = emptyList(),
)

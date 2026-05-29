package com.vyzorix.audiorouter.common.model

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.enums.RouteState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonStatusSerializationTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = false }

    @Test
    fun round_trip_preserves_all_fields() {
        val original = DaemonStatus(
            daemonState = DaemonState.RUNNING,
            routeState = RouteState.SPEAKER_FORCED,
            captureState = CaptureState.ACTIVE,
            riskLevel = RiskLevel.STABLE,
            uptimeMs = 12_345_678L,
            memoryMb = 128,
            thermalC = 42.5f,
            websocketConnected = true,
            lastCommandAtMs = 1_730_000_000_000L,
            notes = listOf("running steady", "no soft-reboot signals"),
        )
        val encoded = json.encodeToString(DaemonStatus.serializer(), original)
        val decoded = json.decodeFromString(DaemonStatus.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun snake_case_field_names_on_the_wire() {
        val status = DaemonStatus(
            daemonState = DaemonState.SAFE_MODE,
            routeState = RouteState.DRIFTING,
            captureState = CaptureState.STARVED,
            riskLevel = RiskLevel.HIGH,
            uptimeMs = 1L,
            memoryMb = 1,
            thermalC = 1.0f,
            websocketConnected = false,
        )
        val encoded = json.encodeToString(DaemonStatus.serializer(), status)

        // Dashboards / Render server consume snake_case — guard the contract.
        listOf(
            "\"daemon_state\"",
            "\"route_state\"",
            "\"capture_state\"",
            "\"risk_level\"",
            "\"uptime_ms\"",
            "\"memory_mb\"",
            "\"thermal_c\"",
            "\"websocket_connected\"",
        ).forEach { needle ->
            assert(needle in encoded) { "expected $needle in $encoded" }
        }
    }
}

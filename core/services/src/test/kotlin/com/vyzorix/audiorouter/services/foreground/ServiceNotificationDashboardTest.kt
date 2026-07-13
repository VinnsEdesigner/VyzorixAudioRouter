package com.vyzorix.audiorouter.services.foreground

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.common.model.DaemonStatus
import com.vyzorix.audiorouter.services.foreground.signals.SignalValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceNotificationDashboardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun mkTick(risk: RiskLevel = RiskLevel.STABLE): AggregatorTick = AggregatorTick(
        status = DaemonStatus(
            daemonState = DaemonState.RUNNING,
            routeState = RouteState.SPEAKER_FORCED,
            captureState = CaptureState.ACTIVE,
            riskLevel = risk,
            uptimeMs = 123_456L,
            memoryMb = 512,
            thermalC = -1f,
            websocketConnected = false,
            lastCommandAtMs = null,
            notes = listOf("note1", "note2"),
        ),
        signals = emptyMap(),
        tickEpochMs = 0L,
    )

    @Test fun `build produces a non-null notification`() {
        val dashboard = ServiceNotificationDashboard(context = context)
        val n = dashboard.build(mkTick())
        assertNotNull(n)
    }

    @Test fun `build creates the daemon channel as a side effect`() {
        val dashboard = ServiceNotificationDashboard(context = context)
        dashboard.build(mkTick())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager.getNotificationChannel(NotificationConstants.CHANNEL_DAEMON))
    }

    @Test fun `build increments buildCount`() {
        val dashboard = ServiceNotificationDashboard(context = context)
        val baseline = dashboard.buildCount()
        dashboard.build(mkTick())
        dashboard.build(mkTick(RiskLevel.HIGH))
        assert(dashboard.buildCount() == baseline + 2)
    }

    @Test fun `render exposes tier one two and three text without RemoteViews introspection`() {
        val dashboard = ServiceNotificationDashboard(context = context)
        val tick = mkTick(RiskLevel.CRITICAL).copy(
            signals = mapOf(
                ServiceNotificationDashboard.PROJECTION_TOKEN_ID to SignalValue.ok("granted"),
                ServiceNotificationDashboard.MEMORY_ID to SignalValue.warn("low", "available=96MB"),
                ServiceNotificationDashboard.THERMAL_ID to SignalValue.crit("hot", "status=4"),
            ),
        )

        val model = dashboard.render(tick)

        kotlin.test.assertEquals("Critical", model.riskBadge)
        kotlin.test.assertEquals("SPEAKER_FORCED", model.routeState)
        kotlin.test.assertEquals("projection token: granted", model.captureDetail)
        kotlin.test.assertEquals("thermal: hot", model.healthThermal)
        kotlin.test.assertEquals(listOf("note1", "note2", "", ""), model.diagnosticNotes)
    }
}

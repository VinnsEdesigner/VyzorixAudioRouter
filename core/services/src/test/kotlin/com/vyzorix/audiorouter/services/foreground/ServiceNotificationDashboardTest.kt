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
}

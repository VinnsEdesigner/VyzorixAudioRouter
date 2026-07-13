// ServiceNotificationDashboard — builds the daemon's RemoteViews-based
// notification surface (Tier 1/2/3 per NOTIFICATION_DASHBOARD.md).
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 612:
//     core/services/foreground/ServiceNotificationDashboard.kt
//       "Builds RemoteViews with live status; updates every 10s".
//
// Composition:
//   - Collapsed view (Tier 1): title + 1-line status + risk badge.
//   - Expanded view (Tier 2 + 3): route card, capture card, health
//     card, diagnostics list.
//   - Three action buttons: Pause/Resume, Restart, Stop. Each wired
//     through the corresponding *Action.buildPendingIntent helper so
//     the receiver topology stays singular.
//   - NotificationCompat.DecoratedCustomViewStyle() so the system
//     chrome (small icon + when row) is preserved while the body is
//     ours.
//
// Refresh cadence: the dashboard does NOT poll on its own. The caller
// (PersistentAudioService) collects an aggregator tick and rebuilds
// the notification on each tick by calling [build] and re-posting via
// NotificationManager.
//
// Layer 5 contract: this class REPLACES the Layer 3 [ServiceNotification]
// surface inside PersistentAudioService.promoteToForeground(). The
// channel ID stays `CHANNEL_DAEMON` so the OS tracks the same channel
// across the transition.

package com.vyzorix.audiorouter.services.foreground

import android.app.Notification
import android.content.Context
import android.widget.RemoteViews
import com.vyzorix.audiorouter.services.R as ServicesR
import com.vyzorix.audiorouter.common.constants.NotificationConstants
import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.common.utils.NotificationChannelManager
import com.vyzorix.audiorouter.services.compat.NotificationCompatBridge
import com.vyzorix.audiorouter.services.foreground.actions.EmergencyStopAction
import com.vyzorix.audiorouter.services.foreground.actions.QuickToggleAction
import com.vyzorix.audiorouter.services.foreground.actions.RestartPipelineAction
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stateful-but-pure dashboard builder. One instance per service.
 *
 * Stateful in the sense that we count how many rebuilds have happened
 * (used by the dashboard tests + the diagnostics tier). Pure because
 * each [build] call is deterministic given its arguments.
 */
public class ServiceNotificationDashboard(
    private val context: Context,
    private val packageContext: Context = context,
) {

    private val rebuilds: AtomicLong = AtomicLong(0L)

    /**
     * Build a pure text model first, then project it into RemoteViews.
     * Keeping this separate makes the Tier 1/2/3 dashboard logic unit-testable
     * without depending on RemoteViews internals.
     */
    public fun render(tick: AggregatorTick): DashboardRenderModel {
        val notes = tick.status.notes.take(DIAGNOSTICS_VISIBLE_LINES)
        return DashboardRenderModel(
            title = TITLE,
            collapsedStatus = "${tick.status.daemonState} · route ${tick.status.routeState}",
            riskBadge = badgeForRisk(tick.status.riskLevel),
            routeState = tick.status.routeState.name,
            routeDetail = "websocket: ${if (tick.status.websocketConnected) "connected" else "—"}",
            captureState = tick.status.captureState.name,
            captureDetail = "projection token: ${tick.signals[PROJECTION_TOKEN_ID]?.label ?: "—"}",
            healthRisk = "Risk: ${tick.status.riskLevel}",
            healthMemory = "memory: ${tick.status.memoryMb}MB · ${tick.signals[MEMORY_ID]?.label ?: "—"}",
            healthThermal = "thermal: ${tick.signals[THERMAL_ID]?.label ?: "—"}",
            healthUptime = "uptime: ${formatUptime(tick.status.uptimeMs)}",
            diagnosticNotes = List(DIAGNOSTICS_VISIBLE_LINES) { index -> notes.getOrElse(index) { if (index == 0) "—" else "" } },
        )
    }

    /**
     * Build the live notification for the most recent
     * [AggregatorTick]. Caller is responsible for posting it via
     * `NotificationManager.notify(NOTIFICATION_ID_DAEMON, notification)`.
     */
    public fun build(tick: AggregatorTick): Notification {
        rebuilds.incrementAndGet()
        NotificationChannelManager.ensureChannels(context)
        val model = render(tick)
        val collapsed = buildCollapsedView(model)
        val expanded = buildExpandedView(model)
        val actions = buildActions()
        val notification = NotificationCompatBridge.buildDashboardNotification(
            context = context,
            smallIconRes = SMALL_ICON_RES,
            collapsedView = collapsed,
            expandedView = expanded,
            channelId = NotificationConstants.CHANNEL_DAEMON,
            contentIntent = null,
            actions = actions,
            contentTitle = TITLE,
            contentText = badgeForRisk(tick.status.riskLevel),
        )
        DaemonLogger.get().verbose(
            TAG,
            "dashboard.build rebuilds=${rebuilds.get()} risk=${tick.status.riskLevel}",
        )
        return notification
    }

    /** Diagnostic: number of times [build] was called. */
    public fun buildCount(): Long = rebuilds.get()

    private fun buildCollapsedView(model: DashboardRenderModel): RemoteViews {
        val rv = RemoteViews(packageContext.packageName, ServicesR.layout.notification_dashboard_collapsed)
        rv.setTextViewText(ServicesR.id.notification_dashboard_collapsed_title, model.title)
        rv.setTextViewText(
            ServicesR.id.notification_dashboard_collapsed_status,
            model.collapsedStatus,
        )
        rv.setTextViewText(
            ServicesR.id.notification_dashboard_collapsed_badge,
            model.riskBadge,
        )
        return rv
    }

    private fun buildExpandedView(model: DashboardRenderModel): RemoteViews {
        val rv = RemoteViews(packageContext.packageName, ServicesR.layout.notification_dashboard_expanded)
        // Route card
        rv.setTextViewText(
            ServicesR.id.notification_section_route_state,
            model.routeState,
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_route_detail,
            model.routeDetail,
        )
        // Capture card
        rv.setTextViewText(
            ServicesR.id.notification_section_capture_state,
            model.captureState,
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_capture_detail,
            model.captureDetail,
        )
        // Health card
        rv.setTextViewText(
            ServicesR.id.notification_section_health_risk,
            model.healthRisk,
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_health_memory,
            model.healthMemory,
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_health_thermal,
            model.healthThermal,
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_health_uptime,
            model.healthUptime,
        )
        // Diagnostics card — up to 4 most-recent notes.
        rv.setTextViewText(
            ServicesR.id.notification_section_diagnostics_note_1,
            model.diagnosticNotes[0],
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_diagnostics_note_2,
            model.diagnosticNotes[1],
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_diagnostics_note_3,
            model.diagnosticNotes[2],
        )
        rv.setTextViewText(
            ServicesR.id.notification_section_diagnostics_note_4,
            model.diagnosticNotes[3],
        )
        return rv
    }

    private fun buildActions(): List<androidx.core.app.NotificationCompat.Action> = listOf(
        NotificationCompatBridge.buildAction(
            iconRes = android.R.drawable.ic_media_pause,
            title = "Pause/Resume",
            pendingIntent = QuickToggleAction.buildPendingIntent(context),
        ),
        NotificationCompatBridge.buildAction(
            iconRes = android.R.drawable.stat_notify_sync,
            title = "Restart",
            pendingIntent = RestartPipelineAction.buildPendingIntent(context),
        ),
        NotificationCompatBridge.buildAction(
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            title = "Stop",
            pendingIntent = EmergencyStopAction.buildPendingIntent(context),
        ),
    )

    private fun badgeForRisk(risk: RiskLevel): String = when (risk) {
        RiskLevel.STABLE -> "OK"
        RiskLevel.ELEVATED -> "Watch"
        RiskLevel.HIGH -> "Degraded"
        RiskLevel.CRITICAL -> "Critical"
    }

    private fun formatUptime(uptimeMs: Long): String {
        if (uptimeMs <= 0L) return "—"
        val secs = uptimeMs / 1_000L
        val hours = secs / 3_600L
        val mins = (secs % 3_600L) / 60L
        val s = secs % 60L
        return "${hours}h${mins}m${s}s"
    }

    public companion object {
        public const val NOTIFICATION_ID: Int = NotificationConstants.NOTIFICATION_ID_DAEMON
        public const val SMALL_ICON_RES: Int = android.R.drawable.stat_notify_voicemail
        public const val TITLE: String = "VyzorixAudioRouter"
        public const val PROJECTION_TOKEN_ID: String = "projection_token"
        public const val MEMORY_ID: String = "memory_pressure"
        public const val THERMAL_ID: String = "thermal"
        public const val DIAGNOSTICS_VISIBLE_LINES: Int = 4
        private const val TAG: String = "ServiceNotificationDashboard"
    }
}


public data class DashboardRenderModel(
    public val title: String,
    public val collapsedStatus: String,
    public val riskBadge: String,
    public val routeState: String,
    public val routeDetail: String,
    public val captureState: String,
    public val captureDetail: String,
    public val healthRisk: String,
    public val healthMemory: String,
    public val healthThermal: String,
    public val healthUptime: String,
    public val diagnosticNotes: List<String>,
)

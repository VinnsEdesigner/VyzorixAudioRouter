// LogExportReceiver — handles the daemon notification's "Export logs" action.
//
// Why a BroadcastReceiver (vs an Activity or Service):
//   - No-ADB constraint means the user must trigger the export from the
//     persistent notification (launcher icon is hidden after Layer 3
//     bootstrap). A receiver is the lightest A14-compatible surface for a
//     notification action button.
//   - We do the heavy work in `goAsync()` so the MediaStore insert can run
//     off the main thread without an Activity context.
//
// Flow:
//   1. User taps "Export logs" → broadcast arrives here.
//   2. We bundle the daemon's FileLogger directory via [LogBundleExporter].
//   3. We mutate the persistent notification text so the user can immediately
//      see where the bundle landed (e.g. "Saved Documents/Vyzorix/...").
//
// Failure modes are reported in the same notification — the dashboard
// (Layer 5+) will add a richer surface but Layer 3.5 just needs the user
// to know whether the export worked.

package com.vyzorix.audiorouter.services.foreground

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.common.constants.FilePaths
import com.vyzorix.audiorouter.common.logging.LogBundleExporter
import com.vyzorix.audiorouter.services.logging.DaemonLogPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receiver that produces a log zip into Documents/Vyzorix and updates the daemon notification. */
public class LogExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT_LOGS) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val statusText = runExport(appContext)
                refreshNotification(appContext, statusText)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun runExport(context: Context): CharSequence {
        val logDir = DaemonLogPaths.logDirectory(context)
        val exporter = exporterFactory(context)
        val result = exporter.export(logDir)
        return when (result) {
            is LogBundleExporter.Result.Saved ->
                "Logs saved to ${result.displayPath}"
            is LogBundleExporter.Result.Empty ->
                "No logs to export yet"
            is LogBundleExporter.Result.Failure ->
                "Log export failed: ${result.cause.message ?: result.cause.javaClass.simpleName}"
        }
    }

    private fun refreshNotification(context: Context, statusText: CharSequence) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val notification = ServiceNotification.build(context, statusText)
        manager.notify(ServiceNotification.NOTIFICATION_ID, notification)
    }

    public companion object {
        /** Intent action fired by the notification's "Export logs" button. */
        public const val ACTION_EXPORT_LOGS: String = "com.vyzorix.audiorouter.action.EXPORT_LOGS"

        /**
         * Factory seam — defaults to the production [LogBundleExporter]. Tests
         * override this to assert routing without touching MediaStore. Keep
         * `internal`-visible so the wider service module can swap the factory
         * but consumers outside the module cannot.
         */
        @Suppress("ConstPropertyName")
        @JvmStatic
        internal var exporterFactory: (Context) -> LogBundleExporter = { context ->
            LogBundleExporter(context = context)
        }
    }
}

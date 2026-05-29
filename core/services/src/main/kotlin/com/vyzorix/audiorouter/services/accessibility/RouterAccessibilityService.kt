// RouterAccessibilityService — bootstrap entrypoint.
//
// Layer 3 contract (per doc/BUILD_ORDER.md): "minimal: only
// onServiceConnected() → LauncherIconHider.nukeLauncherIcon() → start
// PersistentAudioService."
//
// We do NOT consume accessibility events at this stage — that's reserved
// for Layer 4 (permission-screen automation). The service exists at L3
// purely so:
//
//   1. The user can enable a system-level toggle that survives reboots.
//   2. The daemon has a "first launch" hook (onServiceConnected) that
//      fires the moment the user finishes the bootstrap flow.
//
// Threading: onServiceConnected runs on the main thread; we delegate the
// (potentially slow) PackageManager call into a background helper after
// validating prerequisites cheaply.

package com.vyzorix.audiorouter.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.vyzorix.audiorouter.services.bootstrap.LauncherIconHider
import com.vyzorix.audiorouter.services.foreground.PersistentAudioService

/** Bootstrap accessibility service. */
public class RouterAccessibilityService : AccessibilityService() {

    /**
     * Called once the system has bound this service. We:
     *   1. Hide the launcher activity (idempotent).
     *   2. Start [PersistentAudioService] as a foreground service.
     *
     * The bootstrap component is supplied by the host application via the
     * static [Companion.bootstrapComponentName] hook — :core:services does
     * not know the consuming app's activity name. The :app module's
     * [VyzorixApplication] sets the hook at process start.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        val bootstrapComponent = Companion.bootstrapComponentName
            ?: run {
                Log.w(TAG, "Bootstrap component not registered; cannot hide launcher icon.")
                null
            }
        if (bootstrapComponent != null) {
            LauncherIconHider.nukeLauncherIcon(this, bootstrapComponent)
        }
        startDaemon(this)
    }

    /** No-op for Layer 3 — Layer 4 will hook permission automation here. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty per Layer 3 scope.
    }

    /** No-op for Layer 3. */
    override fun onInterrupt() {
        // Intentionally empty per Layer 3 scope.
    }

    public companion object {
        private const val TAG = "RouterA11yService"

        /**
         * Caller-supplied ComponentName of the bootstrap activity to hide
         * once accessibility is enabled. The :app module sets this from
         * its [android.app.Application.onCreate] hook.
         */
        @Volatile
        public var bootstrapComponentName: ComponentName? = null

        /**
         * Start (or no-op if already running) the persistent audio service.
         * Public so [BootStateRestorer] and the :app module's
         * [VyzorixApplication] can route through the same code path.
         */
        public fun startDaemon(context: Context) {
            val launchIntent = Intent(context, PersistentAudioService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(launchIntent)
            } else {
                context.startService(launchIntent)
            }
        }
    }
}

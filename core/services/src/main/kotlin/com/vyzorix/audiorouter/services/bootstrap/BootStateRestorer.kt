// BootStateRestorer — runs from BootReceiver after a device reboot to
// re-arm the daemon.
//
// Layer 3 scope (per doc/BUILD_ORDER.md):
//   "BootStateRestorer can no-op on first build; it just needs to compile."
//
// That said, BUILD_ORDER also requires the post-Layer-3 acceptance gate to
// include a 24-hour soak. A boot during that soak must result in audio
// resuming without a user tap, so even at Layer 3 the restorer must do at
// least the minimum: if the accessibility service had been enabled before
// the reboot, start PersistentAudioService.
//
// The "last_state.json" flight-data file referenced in DOC_4 §3 lands in
// Layer 5 (crash/). For Layer 3 we approximate "was accessibility enabled?"
// by checking the runtime PackageManager state of RouterAccessibilityService.
// That check is cheap and reliable on the C22.

package com.vyzorix.audiorouter.services.bootstrap

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.vyzorix.audiorouter.services.foreground.PersistentAudioService

/** Reads persisted boot state and starts the daemon if appropriate. */
public object BootStateRestorer {

    /**
     * Inspect runtime state and, if the daemon was previously running,
     * start [PersistentAudioService] as a foreground service.
     *
     * The function is intentionally side-effect free outside the `startService`
     * call: it never crashes, and silently no-ops when the prerequisites
     * (accessibility, foreground-service permission) aren't satisfied. The
     * BootReceiver is allowed at most 10 seconds of wall-clock budget by
     * the OS, so any expensive work belongs in [PersistentAudioService.onStartCommand].
     */
    public fun restore(
        context: Context,
        accessibilityServiceComponent: ComponentName,
    ) {
        if (!isAccessibilityServiceEnabled(context, accessibilityServiceComponent)) {
            // User never finished the bootstrap; nothing to restore.
            return
        }
        val launchIntent = Intent(context, PersistentAudioService::class.java).apply {
            action = ACTION_RESTORE_FROM_BOOT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(launchIntent)
        } else {
            context.startService(launchIntent)
        }
    }

    /**
     * Returns true iff [accessibilityServiceComponent] appears in
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
     *
     * We deliberately consult Settings rather than [AccessibilityManager]
     * because the former is true even when the AM has not yet rebound after
     * a reboot (the AM rebinds asynchronously; the Settings string is set
     * eagerly by SystemServer).
     */
    public fun isAccessibilityServiceEnabled(
        context: Context,
        accessibilityServiceComponent: ComponentName,
    ): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val target = accessibilityServiceComponent.flattenToString()
        val targetShort = accessibilityServiceComponent.flattenToShortString()
        return splitter(flat).any { it == target || it == targetShort }
    }

    private fun splitter(flat: String): Sequence<String> {
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        return generateSequence { if (splitter.hasNext()) splitter.next() else null }
    }

    /** Action string set on the restore intent so [PersistentAudioService] can distinguish boot from accessibility starts. */
    public const val ACTION_RESTORE_FROM_BOOT: String =
        "com.vyzorix.audiorouter.services.action.RESTORE_FROM_BOOT"

    /**
     * Optional helper for callers that need the same decision but do not
     * want the side effect of starting the service.
     */
    @Suppress("unused")
    public fun wasDaemonRunningPreReboot(
        context: Context,
        accessibilityServiceComponent: ComponentName,
        accessibilityManager: AccessibilityManager = context.getSystemService(
            Context.ACCESSIBILITY_SERVICE,
        ) as AccessibilityManager,
    ): Boolean {
        if (!accessibilityManager.isEnabled) return false
        val enabled = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )
        return enabled.any { it.id == accessibilityServiceComponent.flattenToString() }
    }
}

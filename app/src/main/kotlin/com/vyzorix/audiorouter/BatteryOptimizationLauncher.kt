// BatteryOptimizationLauncher — kicks off the system battery-optimisation
// whitelist flow.
//
// Per doc/NOKIA_C22_NOTES.md §5 Nokia (Evenwell) silently kills foreground
// services after ~6h of standby if the app is *not* on the battery
// optimisation whitelist. The mitigation is the standard
// REQUEST_IGNORE_BATTERY_OPTIMIZATIONS action — a one-tap whitelist dialog.
//
// This helper is split out of BootstrapActivity so:
//   - The logic can be unit-tested without an Activity context.
//   - Future entry points (e.g. a re-prompt notification action) can reuse it.

package com.vyzorix.audiorouter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Helpers for launching the system battery-opt whitelist UI. */
public object BatteryOptimizationLauncher {

    /** Returns true if the app is currently exempt from battery optimisation. */
    public fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Build the system intent that opens the "Allow background activity?"
     * dialog for this package. Returns null if the host doesn't expose the
     * action — extremely rare on Android 13+ but the BootstrapActivity
     * caller falls back to the generic Battery-Optimisation Settings screen.
     */
    @SuppressLint("BatteryLife")
    public fun ignoreBatteryOptimizationsIntent(packageName: String): Intent {
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is the one-tap dialog.
        // Google Play forbids it for general-purpose apps, but VyzorixAudioRouter
        // is sideloaded (no Play distribution) so this is the correct UX.
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Generic fallback when the one-tap action is missing. */
    public fun batteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

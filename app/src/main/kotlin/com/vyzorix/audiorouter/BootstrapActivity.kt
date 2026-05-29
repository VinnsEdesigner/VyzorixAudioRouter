// BootstrapActivity — single launcher entrypoint.
//
// Per doc/BUILD_ORDER.md §Layer 3 + DOC_2 §2.1, this is the activity the
// user taps from the launcher EXACTLY ONCE. It surfaces a short message
// + a deep-link to system Settings → Accessibility so the user can
// enable RouterAccessibilityService. Once they do:
//
//   - RouterAccessibilityService.onServiceConnected() fires.
//   - LauncherIconHider disables this activity (DONT_KILL_APP).
//   - The launcher icon vanishes from the user's perspective.
//
// Layer 3.5 additions (this file):
//   - "Disable battery optimisation" button — calls
//     BatteryOptimizationLauncher to prompt the user. Without this Nokia
//     kills the daemon after ~6h of standby.
//   - "Export logs now" button — fires the same broadcast as the
//     persistent-notification action. Provides a launcher-icon-visible
//     escape hatch in case the notification surface ever breaks.

package com.vyzorix.audiorouter

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Single-screen launcher bootstrap. */
public class BootstrapActivity : Activity() {

    private var batteryOptButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptimizationLabel()
    }

    /**
     * Build the bootstrap UI programmatically — Layer 3 doesn't pull in
     * AndroidX AppCompat / Material, so we keep this to platform widgets
     * + inline layout. Layer 5+ replaces this with a Compose dashboard.
     */
    private fun buildView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 96)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.bootstrap_title)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        val body = TextView(this).apply {
            text = getString(R.string.bootstrap_body)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        val openSettings = Button(this).apply {
            text = getString(R.string.bootstrap_open_accessibility_settings)
            setOnClickListener {
                openAccessibilitySettings()
            }
        }
        val batteryOpt = Button(this).apply {
            text = getString(R.string.bootstrap_disable_battery_optimization)
            setOnClickListener {
                launchBatteryOptimizationFlow()
            }
        }
        batteryOptButton = batteryOpt
        val exportLogs = Button(this).apply {
            text = getString(R.string.bootstrap_export_logs)
            setOnClickListener {
                triggerLogExport()
            }
        }
        val closeButton = Button(this).apply {
            text = getString(R.string.bootstrap_dismiss)
            setOnClickListener {
                finish()
            }
        }
        container.addView(title)
        container.addView(body)
        container.addView(openSettings)
        container.addView(batteryOpt)
        container.addView(exportLogs)
        container.addView(closeButton)
        return container
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun launchBatteryOptimizationFlow() {
        if (BatteryOptimizationLauncher.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(
                this,
                R.string.bootstrap_battery_optimization_already_disabled,
                Toast.LENGTH_SHORT,
            ).show()
            refreshBatteryOptimizationLabel()
            return
        }
        val direct = BatteryOptimizationLauncher.ignoreBatteryOptimizationsIntent(packageName)
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            // Fall back to the generic settings screen for OEMs that hide the
            // one-tap dialog (rare on A13+).
            startActivity(BatteryOptimizationLauncher.batteryOptimizationSettingsIntent())
        }
    }

    private fun refreshBatteryOptimizationLabel() {
        val button = batteryOptButton ?: return
        button.text = if (BatteryOptimizationLauncher.isIgnoringBatteryOptimizations(this)) {
            getString(R.string.bootstrap_battery_optimization_already_disabled)
        } else {
            getString(R.string.bootstrap_disable_battery_optimization)
        }
    }

    private fun triggerLogExport() {
        // Send the same broadcast the notification action does. Explicit
        // component so the broadcast lands even when implicit-broadcast
        // restrictions (A8+) would otherwise filter it.
        val broadcast = Intent(LOG_EXPORT_ACTION).apply {
            component = ComponentName(
                packageName,
                "com.vyzorix.audiorouter.services.foreground.LogExportReceiver",
            )
            `package` = packageName
        }
        sendBroadcast(broadcast)
    }

    private companion object {
        const val LOG_EXPORT_ACTION: String = "com.vyzorix.audiorouter.action.EXPORT_LOGS"
    }
}

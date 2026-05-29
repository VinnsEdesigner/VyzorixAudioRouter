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
// We intentionally do NOT show a full settings UI here — that lives in
// Layer 5+ when the dashboard lands. Layer 3's bootstrap is one-screen
// minimal.

package com.vyzorix.audiorouter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Single-screen launcher bootstrap. */
public class BootstrapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
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
        val closeButton = Button(this).apply {
            text = getString(R.string.bootstrap_dismiss)
            setOnClickListener {
                finish()
            }
        }
        container.addView(title)
        container.addView(body)
        container.addView(openSettings)
        container.addView(closeButton)
        return container
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

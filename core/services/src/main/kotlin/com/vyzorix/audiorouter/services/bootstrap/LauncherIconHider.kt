// LauncherIconHider — removes the BootstrapActivity from the launcher
// after the user has granted the accessibility permission.
//
// Why we do this:
//   1. Headless daemon by design (DOC_1 §1.2). After the user enables the
//      accessibility service, the launcher icon is dead weight that confuses
//      the user and also tempts the Nokia C22 soft-reboot pattern documented
//      in `doc/NOKIA_C22_NOTES.md` §1 (the launcher tap → system_server
//      crash race shows up most often during a "fresh" launcher tap).
//   2. PackageManager.setComponentEnabledSetting with DONT_KILL_APP is
//      idempotent — call as many times as you like; only the first call
//      mutates the OS state.
//
// The component name we disable is fully qualified because the
// :core:services library does not know the consuming app's package; the
// caller passes a [ComponentName] resolved from `BootstrapActivity::class`.

package com.vyzorix.audiorouter.services.bootstrap

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Disables / re-enables the bootstrap activity in the launcher. */
public object LauncherIconHider {

    /**
     * Disable [bootstrapComponent] in the launcher.
     *
     * Idempotent; safe to call on every accessibility-grant event. Uses
     * `DONT_KILL_APP` so the disabling does not bounce the process (which
     * would orphan the accessibility binding the caller just enabled).
     */
    public fun nukeLauncherIcon(context: Context, bootstrapComponent: ComponentName) {
        context.packageManager.setComponentEnabledSetting(
            bootstrapComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Re-enable [bootstrapComponent] in the launcher (test / recovery only).
     *
     * Production paths should not call this — the daemon is permanently
     * headless from a user's perspective. Exposed for the on-device factory
     * reset path that lands in Layer 5.
     */
    public fun restoreLauncherIcon(context: Context, bootstrapComponent: ComponentName) {
        context.packageManager.setComponentEnabledSetting(
            bootstrapComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Read-only check used by [BootStateRestorer] and the test suite. */
    public fun isLauncherIconHidden(context: Context, bootstrapComponent: ComponentName): Boolean {
        return when (context.packageManager.getComponentEnabledSetting(bootstrapComponent)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> true
            else -> false
        }
    }
}

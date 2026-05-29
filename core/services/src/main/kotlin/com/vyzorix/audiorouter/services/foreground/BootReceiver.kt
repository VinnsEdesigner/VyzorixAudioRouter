// BootReceiver — restores the daemon after a device reboot.
//
// Layer 3 wire (per doc/BUILD_ORDER.md): just re-trigger PersistentAudioService
// via BootStateRestorer when ACTION_BOOT_COMPLETED fires.
//
// Reliability notes:
//   - We register for BOTH ACTION_BOOT_COMPLETED and LOCKED_BOOT_COMPLETED so
//     the daemon survives a credential-encrypted-storage boot (the user
//     hasn't unlocked yet). The actual AudioManager calls in
//     PersistentAudioService are credential-storage independent.
//   - The receiver gets ~10s of execution budget; everything heavy belongs
//     in PersistentAudioService.onStartCommand.

package com.vyzorix.audiorouter.services.foreground

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.bootstrap.BootStateRestorer

/** Receiver wired in the :core:services manifest to fire on boot. */
public class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val component = Companion.accessibilityServiceComponent
            ?: ComponentName(
                context.packageName,
                "com.vyzorix.audiorouter.services.accessibility.RouterAccessibilityService",
            )
        BootStateRestorer.restore(context, component)
    }

    public companion object {
        /**
         * Allows tests / Layer-4 callers to swap the resolved accessibility
         * component. Production wiring leaves this null and the receiver
         * computes the canonical component name from the host package name.
         */
        @Volatile
        public var accessibilityServiceComponent: ComponentName? = null
    }
}

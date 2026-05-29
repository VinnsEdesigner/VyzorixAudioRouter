// VyzorixAppInitializer — process-level init that runs ONCE per process.
//
// Per doc/BUILD_ORDER.md §Layer 3 the initializer is responsible for:
//   1. Registering NotificationChannels (required before any foreground
//      service can post a notification on A13+).
//   2. Resolving the device profile (currently a side-effect of accessing
//      NokiaC22DeviceProfile.current() — see services/oem/).
//   3. Honouring the Zygote-stage safe delay from the device profile by
//      scheduling subsequent risky-init steps through DelayedInitializer.
//
// We deliberately keep this layer thin — Layers 5+ will add a real
// dependency-injection container (Koin/Hilt) and pull these wirings in.

package com.vyzorix.audiorouter

import android.app.Application
import android.content.ComponentName
import com.vyzorix.audiorouter.common.utils.DelayedInitializer
import com.vyzorix.audiorouter.common.utils.NotificationChannelManager
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-level init that runs from [VyzorixApplication.onCreate]. */
public object VyzorixAppInitializer {

    /**
     * Long-lived scope for delayed-init coroutines. Sits on Dispatchers.Default;
     * we never cancel it (the daemon is process-bound).
     */
    public val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Idempotent process-level bootstrap. */
    public fun initialize(
        application: Application,
        bootstrapComponent: ComponentName,
        accessibilityServiceComponent: ComponentName,
    ) {
        // 1. Notification channels — must exist before
        //    PersistentAudioService.startForeground posts.
        NotificationChannelManager.ensureChannels(application)

        // 2. Device profile (cached after first call).
        val profile = NokiaC22DeviceProfile.current()

        // 3. Defer risky operations past the Nokia C22 Zygote-stage race
        //    window (NOKIA_C22_NOTES §1). For Layer 3 the only deferred
        //    step is... nothing (the heavy lifting is downstream in the
        //    PersistentAudioService.onStartCommand path which is already
        //    triggered later via the accessibility-service onServiceConnected
        //    callback). We still schedule a no-op block here so the
        //    initializer pipeline is wired and Layer 4+ can add real bodies
        //    without changing the call site.
        val zygoteDelay = profile.rawProfile.zygoteSafeDelayMs
        if (zygoteDelay > 0L) {
            DelayedInitializer(applicationScope).schedule(zygoteDelay) {
                onZygoteSafeWindowOpen(
                    application = application,
                    bootstrapComponent = bootstrapComponent,
                    accessibilityServiceComponent = accessibilityServiceComponent,
                )
            }
        }
    }

    /** Hook for Layer 4+ to drop heavier init into; intentionally empty at L3. */
    @Suppress("unused", "UNUSED_PARAMETER")
    private suspend fun onZygoteSafeWindowOpen(
        application: Application,
        bootstrapComponent: ComponentName,
        accessibilityServiceComponent: ComponentName,
    ) {
        // Intentionally empty — Layer 4+ will populate this.
    }
}

// VyzorixApplication — process entrypoint.
//
// Per doc/BUILD_ORDER.md §Layer 3, the app's onCreate must:
//   1. Run VyzorixAppInitializer (notification channels + device profile resolution).
//   2. Register the bootstrap component with RouterAccessibilityService so
//      the latter can disable the launcher icon once the user enables the
//      accessibility service.
//
// Crucially, we do NOT start PersistentAudioService here — the
// accessibility service is the bootstrap entrypoint (DOC_2 §2.1). Starting
// the service unconditionally from Application.onCreate would race with
// the Zygote-stage instability documented in NOKIA_C22_NOTES §1.

package com.vyzorix.audiorouter

import android.app.Application
import android.content.ComponentName
import com.vyzorix.audiorouter.services.accessibility.RouterAccessibilityService
import com.vyzorix.audiorouter.services.foreground.BootReceiver

/** Application subclass for the :app module. */
public class VyzorixApplication : Application() {

    /** Lazily-resolved bootstrap component pointer, exposed for testability. */
    public val bootstrapComponent: ComponentName by lazy {
        ComponentName(this, BootstrapActivity::class.java)
    }

    /** Lazily-resolved accessibility component pointer. */
    public val accessibilityServiceComponent: ComponentName by lazy {
        ComponentName(this, RouterAccessibilityService::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        VyzorixAppInitializer.initialize(
            application = this,
            bootstrapComponent = bootstrapComponent,
            accessibilityServiceComponent = accessibilityServiceComponent,
        )
        RouterAccessibilityService.bootstrapComponentName = bootstrapComponent
        BootReceiver.accessibilityServiceComponent = accessibilityServiceComponent
    }
}

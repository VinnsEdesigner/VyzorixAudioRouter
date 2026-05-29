package com.vyzorix.audiorouter.services.bootstrap

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LauncherIconHiderTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val bootstrapComponent: ComponentName
        get() = ComponentName(context.packageName, "$pkg.BootstrapActivity")

    private val pkg: String get() = context.packageName

    @Test
    fun `nukeLauncherIcon flips the component to DISABLED`() {
        LauncherIconHider.nukeLauncherIcon(context, bootstrapComponent)
        val state = context.packageManager.getComponentEnabledSetting(bootstrapComponent)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state)
    }

    @Test
    fun `isLauncherIconHidden reflects the toggled state`() {
        assertFalse(LauncherIconHider.isLauncherIconHidden(context, bootstrapComponent))
        LauncherIconHider.nukeLauncherIcon(context, bootstrapComponent)
        assertTrue(LauncherIconHider.isLauncherIconHidden(context, bootstrapComponent))
    }

    @Test
    fun `restoreLauncherIcon re-enables the component`() {
        LauncherIconHider.nukeLauncherIcon(context, bootstrapComponent)
        LauncherIconHider.restoreLauncherIcon(context, bootstrapComponent)
        val state = context.packageManager.getComponentEnabledSetting(bootstrapComponent)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state)
        assertFalse(LauncherIconHider.isLauncherIconHidden(context, bootstrapComponent))
    }

    @Test
    fun `nukeLauncherIcon is idempotent`() {
        LauncherIconHider.nukeLauncherIcon(context, bootstrapComponent)
        LauncherIconHider.nukeLauncherIcon(context, bootstrapComponent)
        LauncherIconHider.nukeLauncherIcon(context, bootstrapComponent)
        assertTrue(LauncherIconHider.isLauncherIconHidden(context, bootstrapComponent))
    }
}

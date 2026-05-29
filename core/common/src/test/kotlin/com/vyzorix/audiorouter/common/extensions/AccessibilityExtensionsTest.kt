package com.vyzorix.audiorouter.common.extensions

import android.view.accessibility.AccessibilityEvent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccessibilityExtensionsTest {

    @Test
    fun get_window_package_name_returns_package_string() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = "com.android.systemui"
        assertEquals("com.android.systemui", event.getWindowPackageName())
        event.recycle()
    }

    @Test
    fun get_window_package_name_returns_null_when_absent() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        // Default packageName on a fresh AccessibilityEvent is null.
        assertNull(event.getWindowPackageName())
        event.recycle()
    }
}

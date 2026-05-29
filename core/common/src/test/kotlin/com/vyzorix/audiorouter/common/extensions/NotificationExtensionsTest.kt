package com.vyzorix.audiorouter.common.extensions

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationExtensionsTest {

    @Test
    fun extract_visible_text_joins_title_and_body() {
        val notification = Notification()
        notification.extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "Vyzorix")
            putCharSequence(Notification.EXTRA_TEXT, "Speaker route enforced")
        }
        assertEquals("Vyzorix — Speaker route enforced", notification.extractVisibleText())
    }

    @Test
    fun extract_visible_text_falls_back_to_title_only() {
        val notification = Notification()
        notification.extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "Daemon idle")
        }
        assertEquals("Daemon idle", notification.extractVisibleText())
    }

    @Test
    fun apply_text_style_handles_null_and_empty_input() {
        val empty: String? = null
        assertEquals(0, empty.applyTextStyle().length)
        assertEquals(0, "".applyTextStyle().length)
    }

    @Test
    fun apply_text_style_renders_basic_html() {
        val styled = "<b>bold</b>".applyTextStyle()
        // Just the text content is asserted — span representation differs across runtimes.
        assertEquals("bold", styled.toString())
    }

    @Test
    fun application_label_returns_package_name_or_label() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val label = ctx.applicationLabel()
        // Robolectric defaults to a synthetic package name; either way the
        // returned string must be non-empty.
        assertTrue(label.isNotEmpty())
    }
}

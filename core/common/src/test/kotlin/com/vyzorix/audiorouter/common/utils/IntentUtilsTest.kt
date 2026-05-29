package com.vyzorix.audiorouter.common.utils

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntentUtilsTest {

    @Test
    fun `mutabilityFlag is FLAG_IMMUTABLE when mutable=false`() {
        assertEquals(PendingIntent.FLAG_IMMUTABLE, IntentUtils.mutabilityFlag(mutable = false))
    }

    @Test
    fun `mutabilityFlag is FLAG_MUTABLE on A12 and above when mutable=true`() {
        // sdk=33 → API S+, must return FLAG_MUTABLE
        assertEquals(PendingIntent.FLAG_MUTABLE, IntentUtils.mutabilityFlag(mutable = true))
    }

    @Test
    fun `activityPendingIntent produces a non-null PendingIntent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().setClassName(context.packageName, "${context.packageName}.Activity")
        val pending = IntentUtils.activityPendingIntent(
            context = context,
            requestCode = 42,
            intent = intent,
        )
        assertNotNull(pending)
    }

    @Test
    fun `describeForLog reports action, component, and categories without extras`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName("com.example", "com.example.Foo")
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("token", "super-secret-do-not-log")
        }
        val description = IntentUtils.describeForLog(intent)
        assertTrue(description.contains(Intent.ACTION_VIEW))
        assertTrue(description.contains("com.example/.Foo"))
        assertTrue(description.contains(Intent.CATEGORY_DEFAULT))
        assertEquals(false, description.contains("super-secret-do-not-log"))
    }

    @Test
    fun `describeForLog handles null gracefully`() {
        assertEquals("Intent(null)", IntentUtils.describeForLog(null))
    }
}

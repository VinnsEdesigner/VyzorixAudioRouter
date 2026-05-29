package com.vyzorix.audiorouter.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogBundleExporterTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `Empty result when log directory does not exist`() {
        val exporter = LogBundleExporter(context = context)
        val result = exporter.export(File(context.filesDir, "logs-that-never-existed"))
        assertTrue(result is LogBundleExporter.Result.Empty)
    }

    @Test
    fun `Empty result when log directory exists but is empty`() {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val exporter = LogBundleExporter(
            context = context,
            // Force zipper to report zero bytes when there's nothing to ship.
            zipper = LogBundleExporter.Zipper { _, _ -> 0L },
            nowMillis = { 1700000000000L },
        )
        val result = exporter.export(dir)
        assertTrue(result is LogBundleExporter.Result.Empty)
    }

    @Test
    fun `Saved result reports display path under Documents Vyzorix when content present`() {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        File(dir, "rolling.log").writeText("entry\n")
        val exporter = LogBundleExporter(
            context = context,
            zipper = LogBundleExporter.Zipper { _, out ->
                out.write("PK\u0003\u0004".toByteArray())
                4L
            },
            nowMillis = { 1700000000000L },
        )
        val result = exporter.export(dir)
        val saved = result as? LogBundleExporter.Result.Saved
        assertNotNull(saved)
        assertEquals("Documents/Vyzorix/vyzorix-logs-1700000000000.zip", saved.displayPath)
        assertEquals(4L, saved.sourceBytes)
    }

    @Test
    fun `Failure result when the zipper throws`() {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        File(dir, "rolling.log").writeText("entry\n")
        val exporter = LogBundleExporter(
            context = context,
            zipper = LogBundleExporter.Zipper { _, _ -> error("boom") },
        )
        val result = exporter.export(dir)
        val failure = result as? LogBundleExporter.Result.Failure
        assertNotNull(failure)
        assertEquals("boom", failure.cause.message)
    }
}

package com.vyzorix.audiorouter.common.logging

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogBundleZipperTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `zips every default-filter file in lexicographic order`() {
        val dir = temp.newFolder()
        // Mixed order on disk; filter should accept all three and emit in name order.
        File(dir, "rolling.log.1700000001000.rolled").writeText("entry-A\n")
        File(dir, "rolling.log").writeText("entry-B\n")
        File(dir, "rolling.log.1700000002000.rolled").writeText("entry-C\n")
        // Non-log artefacts must be skipped.
        File(dir, "crash_snapshot.zip").writeText("ignored\n")

        val out = ByteArrayOutputStream()
        val bytes = LogBundleZipper.zipDirectory(dir, out)

        assertTrue(bytes > 0, "Should report compressed source bytes")
        val entries = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        // Lexicographic: `rolling.log` < `rolling.log.1...` < `rolling.log.2...`
        assertEquals(
            listOf(
                "rolling.log",
                "rolling.log.1700000001000.rolled",
                "rolling.log.1700000002000.rolled",
            ),
            entries,
        )
    }

    @Test
    fun `skips empty files`() {
        val dir = temp.newFolder()
        File(dir, "rolling.log").writeText("") // empty -> skipped by default filter
        File(dir, "rolling.log.1700000001000.rolled").writeText("real-data\n")

        val out = ByteArrayOutputStream()
        LogBundleZipper.zipDirectory(dir, out)

        val entries = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        assertEquals(listOf("rolling.log.1700000001000.rolled"), entries)
    }

    @Test
    fun `returns 0 for a missing or empty directory`() {
        val missing = File(temp.root, "does-not-exist")
        assertEquals(0L, LogBundleZipper.zipDirectory(missing, ByteArrayOutputStream()))

        val empty = temp.newFolder()
        assertEquals(0L, LogBundleZipper.zipDirectory(empty, ByteArrayOutputStream()))
    }

    @Test
    fun `does not close the caller's output stream`() {
        val dir = temp.newFolder()
        File(dir, "rolling.log").writeText("entry\n")
        val out = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }
        LogBundleZipper.zipDirectory(dir, out)
        assertTrue(!out.closed, "Zipper must not close the caller-owned output stream")
    }
}

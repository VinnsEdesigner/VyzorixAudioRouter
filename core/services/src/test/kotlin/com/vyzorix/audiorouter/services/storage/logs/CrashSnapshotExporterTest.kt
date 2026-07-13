package com.vyzorix.audiorouter.services.storage.logs

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashSnapshotExporterTest {
    @Test
    fun exportCreatesZipWithSourceFilesAndChecksum() {
        val root = createTempDir(prefix = "vyzorix-diag")
        val source = File(root, "source").also { it.mkdirs() }
        File(source, "current_session.log").writeText("hello")
        val output = File(root, "out")

        val snapshot = CrashSnapshotExporter().export(source, output, "bundle.zip")

        assertTrue(snapshot.file.exists())
        assertEquals(64, snapshot.sha256.length)
        ZipFile(snapshot.file).use { zip ->
            assertEquals("hello", zip.getInputStream(zip.getEntry("current_session.log")).reader().readText())
        }
        root.deleteRecursively()
    }
}

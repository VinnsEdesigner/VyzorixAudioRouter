package com.vyzorix.audiorouter.services.storage.logs

import com.vyzorix.audiorouter.services.storage.CrashBundleRetentionPolicy
import com.vyzorix.audiorouter.services.storage.RetentionResult
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a diagnostic ZIP atomically and returns metadata for sharing/upload. */
public class CrashSnapshotExporter(
    private val retentionPolicy: CrashBundleRetentionPolicy = CrashBundleRetentionPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    public fun export(sourceDir: File, outputDir: File, name: String = defaultName()): CrashSnapshot {
        require(sourceDir.exists() && sourceDir.isDirectory) { "sourceDir must exist and be a directory" }
        outputDir.mkdirs()
        val target = File(outputDir, name)
        val temp = File(outputDir, "$name.tmp")
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            sourceDir.walkTopDown()
                .filter { it.isFile && it != temp && it != target }
                .sortedBy { it.relativeTo(sourceDir).path }
                .forEach { file ->
                    val entry = ZipEntry(file.relativeTo(sourceDir).path)
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Failed to finalize diagnostic zip ${target.absolutePath}" }
        val checksum = sha256(target)
        val retention = retentionPolicy.enforce(outputDir)
        return CrashSnapshot(target, target.length(), checksum, retention)
    }

    private fun defaultName(): String = "diagnostics_${clock()}.zip"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

public data class CrashSnapshot(
    public val file: File,
    public val sizeBytes: Long,
    public val sha256: String,
    public val retention: RetentionResult,
)

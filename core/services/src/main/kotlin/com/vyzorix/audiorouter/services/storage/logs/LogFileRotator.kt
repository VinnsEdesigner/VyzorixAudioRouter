package com.vyzorix.audiorouter.services.storage.logs

import java.io.File

/** Rotates current_session.log once it crosses the documented 2 MiB cap. */
public class LogFileRotator(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxArchives: Int = DEFAULT_MAX_ARCHIVES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    public fun rotateIfNeeded(activeLog: File): RotationResult {
        if (!activeLog.exists() || activeLog.length() <= maxBytes) {
            return RotationResult.NotRotated(activeLog.length())
        }
        activeLog.parentFile?.mkdirs()
        val archive = nextArchiveFile(activeLog)
        val renamed = activeLog.renameTo(archive)
        if (!renamed) {
            activeLog.copyTo(archive, overwrite = false)
            activeLog.writeText("")
        }
        val purged = purge(activeLog.parentFile ?: archive.parentFile)
        return RotationResult.Rotated(archive, purged)
    }

    public fun purge(directory: File?): List<File> {
        if (directory == null || !directory.exists()) return emptyList()
        val archives = directory
            .listFiles { file -> file.isFile && file.name.startsWith(ARCHIVE_PREFIX) && file.extension == "log" }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()
        return archives.drop(maxArchives).filter { it.delete() }
    }

    private fun nextArchiveFile(activeLog: File): File {
        val directory = activeLog.parentFile ?: File(".")
        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) "" else "_$attempt"
            val candidate = File(directory, "$ARCHIVE_PREFIX${clock()}$suffix.log")
            if (!candidate.exists()) return candidate
            attempt += 1
        }
    }

    public companion object {
        public const val DEFAULT_MAX_BYTES: Long = 2L * 1024L * 1024L
        public const val DEFAULT_MAX_ARCHIVES: Int = 10
        public const val ARCHIVE_PREFIX: String = "crash_bundle_"
    }
}

public sealed class RotationResult {
    public data class NotRotated(public val currentBytes: Long) : RotationResult()
    public data class Rotated(public val archive: File, public val purged: List<File>) : RotationResult()
}

package com.vyzorix.audiorouter.common.logging

import com.vyzorix.audiorouter.common.constants.AppConstants
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe disk-backed [Logger].
 *
 * Persistent log used by Layer 5+ services (crash forensics, diagnostic
 * bundles). Writes are serialised through a [ReentrantLock] so concurrent
 * callers can't interleave half-written lines.
 *
 * Rolling behaviour:
 *   - Every write checks the file size. When the file exceeds
 *     [maxFileSizeBytes], it is renamed to `<file>.<epoch>.rolled` and a
 *     fresh file is started.
 *   - Rolled files older than the [maxRolledFiles] cap are deleted on
 *     each roll so the on-disk footprint is bounded.
 *
 * This is intentionally simpler than a "real" rolling logger — the
 * daemon's log volume is low (< 1 MB/day in steady state) and the
 * Nokia C22's eMMC favours simple sequential writes.
 */
public class FileLogger(
    public val file: File,
    public val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    public val maxRolledFiles: Int = DEFAULT_MAX_ROLLED_FILES,
    private val tagPrefix: String = AppConstants.LOG_TAG_PREFIX,
    private val now: () -> Long = System::currentTimeMillis,
) : Logger {

    private val lock = ReentrantLock()

    override fun verbose(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.VERBOSE, tag, message, throwable)
    }

    override fun debug(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.DEBUG, tag, message, throwable)
    }

    override fun info(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.INFO, tag, message, throwable)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.WARN, tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.ERROR, tag, message, throwable)
    }

    private fun emit(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val line = TimestampedLogFormatter.format(
            epochMillis = now(),
            level = level,
            tag = tagPrefix + tag,
            message = message,
            throwable = throwable,
        )
        lock.withLock {
            ensureParentDir()
            rollIfNeeded()
            writeLine(line, throwable)
        }
    }

    private fun ensureParentDir() {
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    }

    private fun rollIfNeeded() {
        if (!file.exists() || file.length() < maxFileSizeBytes) return
        val rolled = File(file.parentFile, "${file.name}.${now()}.rolled")
        // Best-effort rename; if rename fails we just keep appending — the
        // failure mode is "log file grew slightly past the cap" which is
        // strictly preferable to losing log lines.
        file.renameTo(rolled)
        pruneRolledFiles()
    }

    private fun pruneRolledFiles() {
        val parent = file.parentFile ?: return
        val rolled = parent.listFiles { f ->
            f.isFile && f.name.startsWith("${file.name}.") && f.name.endsWith(".rolled")
        } ?: return
        if (rolled.size <= maxRolledFiles) return
        rolled
            .sortedByDescending { it.lastModified() }
            .drop(maxRolledFiles)
            .forEach { it.delete() }
    }

    private fun writeLine(line: String, throwable: Throwable?) {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, /* append = */ true), Charsets.UTF_8)).use { writer ->
            writer.write(line)
            writer.newLine()
            throwable?.let {
                val sw = StringWriter()
                PrintWriter(sw).use { pw -> it.printStackTrace(pw) }
                writer.write(sw.toString())
                writer.newLine()
            }
            writer.flush()
        }
    }

    public companion object {
        public const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 1L * 1024L * 1024L // 1 MiB
        public const val DEFAULT_MAX_ROLLED_FILES: Int = 4
    }
}

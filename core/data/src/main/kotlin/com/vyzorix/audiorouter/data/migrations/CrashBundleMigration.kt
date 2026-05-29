package com.vyzorix.audiorouter.data.migrations

import android.content.Context
import com.vyzorix.audiorouter.data.dao.CrashEventDao
import com.vyzorix.audiorouter.data.entity.CrashEvent
import com.vyzorix.audiorouter.common.enums.CrashType
import java.io.File

/**
 * One-time migration from the pre-Room "crash bundle" JSON files into the
 * `crash_events` Room table.
 *
 * The legacy format (used before this layer landed) was a directory of
 * `<filesDir>/crash_bundles/<id>.json` files written by an early
 * `GlobalExceptionHandler` prototype. This migration reads each file,
 * inserts an equivalent `CrashEvent` row, and deletes the source file on
 * success.
 *
 * Why a class rather than a Room migration: the source is files on disk,
 * not a previous SQLite schema, so this runs out-of-band from the Room
 * migration graph. Layer 6's `CrashEventRecorder` invokes it on the first
 * boot after install.
 *
 * Idempotency: each crash bundle's filename is its primary key for this
 * migration; we delete the file after a successful insert so re-running
 * the migration is a no-op once the directory is empty.
 */
public class CrashBundleMigration(
    private val context: Context,
    private val crashEventDao: CrashEventDao,
) {

    /** Directory holding pre-Room crash bundles. */
    public val sourceDirectory: File = File(context.applicationContext.filesDir, BUNDLE_DIRECTORY_NAME)

    /**
     * Runs the migration. Safe to call on every cold start — returns
     * immediately if [sourceDirectory] is absent or empty.
     *
     * @return number of bundles successfully migrated (0 when there's
     *   nothing to do).
     */
    public suspend fun migrate(): Int {
        val dir = sourceDirectory
        if (!dir.exists() || !dir.isDirectory) {
            return 0
        }

        var migrated = 0
        val files = dir.listFiles { f -> f.isFile && f.extension == BUNDLE_EXTENSION } ?: emptyArray()
        for (file in files) {
            val event = runCatching { parseBundle(file) }.getOrNull() ?: continue
            crashEventDao.insert(event)
            // Best-effort delete. If the delete fails we will re-migrate
            // on next boot, which is harmless because `consecutiveCrashes`
            // is reconstructed from `recent()` at read time.
            file.delete()
            migrated++
        }
        return migrated
    }

    /**
     * Parses a legacy bundle into a [CrashEvent]. The format is a very
     * small subset of JSON (key=value lines), kept here verbatim from the
     * pre-Layer-1 prototype so we don't need a JSON dependency for this
     * single migration. The body is intentionally minimal — anything we
     * can't parse becomes a `CrashType.APP_BUG` placeholder so we lose no
     * data, just structure.
     *
     * Layer 6 (`CrashEventRecorder`) will eventually replace this with a
     * richer parser once the canonical bundle format is fixed; this
     * implementation only needs to handle the rows written by the
     * pre-Room prototype.
     */
    private fun parseBundle(file: File): CrashEvent {
        val text = file.readText(Charsets.UTF_8)
        val fields = text.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val epochMs = fields["epochMs"]?.toLongOrNull() ?: file.lastModified()
        val typeName = fields["crashType"]
        val crashType = runCatching {
            typeName?.let { CrashType.valueOf(it) }
        }.getOrNull() ?: CrashType.APP_BUG
        val signature = fields["signature"]?.takeIf { it.isNotBlank() } ?: "legacy.unknown"
        val stackHead = fields["stackHead"]?.take(MAX_STACK_HEAD_LEN).orEmpty()
        val processUptimeMs = fields["processUptimeMs"]?.toLongOrNull() ?: 0L
        val consecutiveCrashes = fields["consecutiveCrashes"]?.toIntOrNull() ?: 0

        return CrashEvent(
            epochMs = epochMs,
            crashType = crashType,
            signature = signature,
            stackHead = stackHead,
            processUptimeMs = processUptimeMs,
            consecutiveCrashes = consecutiveCrashes,
        )
    }

    public companion object {
        public const val BUNDLE_DIRECTORY_NAME: String = "crash_bundles"
        public const val BUNDLE_EXTENSION: String = "json"

        /** Mirrors `CrashEvent.stackHead`'s 4 kB cap. */
        public const val MAX_STACK_HEAD_LEN: Int = 4 * 1024
    }
}

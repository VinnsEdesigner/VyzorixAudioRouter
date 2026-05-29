// LogBundleExporter — Android-side glue that drops a zipped FileLogger bundle
// into a stock-file-manager-reachable path.
//
// Why this matters (no-ADB acceptance gate):
//   The Nokia C22 is the only test device and there is no PC available, so
//   `adb pull` is off the table. The user's only retrieval mechanism is the
//   stock Files-by-Google app navigating to /Documents or /Downloads. This
//   class is the bridge between FileLogger's app-private directory and that
//   public surface.
//
// MediaStore (Q+) is used over legacy File I/O so we never need
// READ_/WRITE_EXTERNAL_STORAGE permission — it's a "scoped" insert that
// targets `Documents/Vyzorix/` regardless of OEM customisation.
//
// On pre-Q the app's own external-files dir is used as the fallback target,
// which Files-by-Google can still browse via the "internal storage > Android
// > data > <pkg>" path.  This is a documented edge — Layer 3.5's no-ADB
// workflow targets Android 13 (Nokia C22), so the Q+ path is the load-bearing
// one.

package com.vyzorix.audiorouter.common.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Exports a [FileLogger]'s directory as a single zip the user can find with
 * the stock file manager.
 *
 * Thread safety: this class is stateless. Each [export] call is independent.
 * The export is synchronous — callers must invoke it off the main thread.
 */
public class LogBundleExporter(
    private val context: Context,
    private val zipper: Zipper = DefaultZipper,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** Result of an export attempt. */
    public sealed class Result {
        /** Success — caller can render the human-readable [displayPath] to the user. */
        public data class Saved(
            val uri: Uri,
            val displayPath: String,
            val sourceBytes: Long,
        ) : Result()

        /** No log lines have been written yet; nothing to bundle. */
        public object Empty : Result()

        /** Underlying I/O blew up. [cause] is preserved for diagnostics. */
        public data class Failure(val cause: Throwable) : Result()
    }

    /**
     * Bundle [logDirectory] into a zip and persist it to a user-reachable
     * location. The returned [Result] tells the caller which notification
     * to surface.
     */
    public fun export(logDirectory: File): Result {
        if (!logDirectory.exists()) return Result.Empty
        val filename = "vyzorix-logs-${nowMillis()}.zip"
        return runCatching {
            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(logDirectory, filename)
            } else {
                exportToLegacyExternal(logDirectory, filename)
            }
            saved ?: Result.Empty
        }.getOrElse { Result.Failure(it) }
    }

    private fun exportViaMediaStore(logDirectory: File, filename: String): Result.Saved? {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_ZIP)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore.insert returned null")
        val bytes = resolver.openOutputStream(uri).use { stream ->
            if (stream == null) throw IOException("openOutputStream returned null for $uri")
            zipper.zipDirectory(logDirectory, stream)
        }
        if (bytes <= 0L) {
            resolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, finalize, null, null)
        }
        return Result.Saved(
            uri = uri,
            displayPath = "Documents/Vyzorix/$filename",
            sourceBytes = bytes,
        )
    }

    private fun exportToLegacyExternal(logDirectory: File, filename: String): Result.Saved? {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IOException("getExternalFilesDir returned null")
        if (!externalDir.exists() && !externalDir.mkdirs()) {
            throw IOException("Unable to create $externalDir")
        }
        val target = File(externalDir, filename)
        val bytes = target.outputStream().use { stream ->
            zipper.zipDirectory(logDirectory, stream)
        }
        if (bytes <= 0L) {
            target.delete()
            return null
        }
        return Result.Saved(
            uri = Uri.fromFile(target),
            displayPath = target.absolutePath,
            sourceBytes = bytes,
        )
    }

    /** Pluggable zipper seam — exists for test injection only. */
    public fun interface Zipper {
        public fun zipDirectory(directory: File, output: OutputStream): Long
    }

    private companion object {
        const val MIME_ZIP: String = "application/zip"
        const val RELATIVE_PATH: String = "Documents/Vyzorix"
        val DefaultZipper: Zipper = Zipper { dir, out -> LogBundleZipper.zipDirectory(dir, out) }
    }
}

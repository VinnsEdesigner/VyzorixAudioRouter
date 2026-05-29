// LogBundleZipper — pure-JVM zip of a FileLogger directory.
//
// Kept in :core:common (no Android imports) so the algorithm is unit-testable
// without Robolectric. The Android-side LogBundleExporter (Layer 3) calls into
// this to produce the actual bytes; the exporter is responsible for piping
// those bytes into MediaStore (or any other output sink).
//
// Why a single zip (vs raw .txt): the Nokia C22 ships with stock Files-by-Google
// which previews .txt happily but breaks badly on multi-file selections. Bundling
// gives the user one file to long-press → Share, which is the only realistic
// off-device transfer mechanism without ADB.

package com.vyzorix.audiorouter.common.logging

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stateless zip builder for [FileLogger] directories. */
public object LogBundleZipper {

    /**
     * Zip every file in [directory] matching [filter] into [output].
     *
     * Files are written in lexicographic order so the zip is stable across
     * runs — important when comparing bundles produced from two devices.
     * The current rolling.log lands first (alphabetically earliest); rolled
     * snapshots follow in epoch order.
     *
     * Does NOT close [output]; callers own the sink lifecycle so they can
     * stream straight into a MediaStore content URI (which fails if we
     * close the descriptor early).
     *
     * @return the number of bytes of source content compressed (not the
     *   resulting zip size — that's whatever ended up in [output]).
     */
    public fun zipDirectory(
        directory: File,
        output: OutputStream,
        filter: (File) -> Boolean = ::defaultFilter,
    ): Long {
        if (!directory.isDirectory) return 0L
        val files = directory.listFiles { f -> f.isFile && filter(f) }
            ?.sortedBy { it.name }
            ?: return 0L
        if (files.isEmpty()) return 0L
        var totalSourceBytes = 0L
        val zip = ZipOutputStream(output)
        try {
            for (file in files) {
                val entry = ZipEntry(file.name).apply {
                    time = file.lastModified()
                    size = file.length()
                }
                zip.putNextEntry(entry)
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zip.write(buffer, 0, read)
                        totalSourceBytes += read
                    }
                }
                zip.closeEntry()
            }
        } finally {
            zip.finish()
            zip.flush()
        }
        return totalSourceBytes
    }

    /**
     * Default file filter — includes the live `rolling.log` and every
     * `*.rolled` snapshot the FileLogger has produced. Excludes anything
     * else in the directory (other artefacts like flight recorder dumps
     * are exported by their own bundlers in Layer 6+).
     */
    public fun defaultFilter(file: File): Boolean {
        if (file.length() <= 0L) return false
        val name = file.name
        return name == "rolling.log" ||
            name.startsWith("rolling.log.") ||
            name.endsWith(".rolled")
    }

    private const val BUFFER_SIZE: Int = 16 * 1024
}

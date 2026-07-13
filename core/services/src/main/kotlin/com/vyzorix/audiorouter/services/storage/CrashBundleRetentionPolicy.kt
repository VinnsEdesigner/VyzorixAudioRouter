package com.vyzorix.audiorouter.services.storage

import java.io.File

/** Enforces the documented cap of 10 bundles and 25 MiB total diagnostics. */
public class CrashBundleRetentionPolicy(
    private val maxBundles: Int = DEFAULT_MAX_BUNDLES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    public fun enforce(directory: File): RetentionResult {
        if (!directory.exists()) return RetentionResult(emptyList(), 0L)
        val bundles = directory
            .listFiles { file -> file.isFile && isBundle(file) }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()
        var keptBytes = 0L
        val deleted = mutableListOf<File>()
        bundles.forEachIndexed { index, file ->
            keptBytes += file.length()
            if (index >= maxBundles || keptBytes > maxTotalBytes) {
                if (file.delete()) deleted += file
            }
        }
        val remainingBytes = directory
            .listFiles { file -> file.isFile && isBundle(file) }
            ?.sumOf { it.length() }
            ?: 0L
        return RetentionResult(deleted, remainingBytes)
    }

    private fun isBundle(file: File): Boolean = file.extension == "zip" || file.name.startsWith("crash_bundle_")

    public companion object {
        public const val DEFAULT_MAX_BUNDLES: Int = 10
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 25L * 1024L * 1024L
    }
}

public data class RetentionResult(
    public val deleted: List<File>,
    public val remainingBytes: Long,
)

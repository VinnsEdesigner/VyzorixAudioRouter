// DaemonLogger — singleton FileLogger pointed at the daemon's canonical log
// directory.
//
// Why a singleton (vs a per-service constructor parameter):
//   - The route-war engines (SpeakerForceEngine, AudioModeKeeper,
//     RoutePersistenceDaemon, VoipAudioAnchor) are leaf coroutines that fire
//     hundreds of times per minute. Plumbing a Logger reference through every
//     constructor adds noise without value — they all want the same FileLogger.
//   - The chunk size (2 MB) and rolled-file count (8) are determined globally
//     by the no-ADB acceptance gate, not per-call-site.
//
// Lifecycle: the singleton is initialised by [PersistentAudioService.onCreate]
// (after [DaemonLogPaths] resolves the filesDir). Calls before init are safe
// — they fall back to a console logger so we never crash on a stray log call
// during VyzorixApplication.onCreate.

package com.vyzorix.audiorouter.services.logging

import android.content.Context
import com.vyzorix.audiorouter.common.logging.ConsoleLogger
import com.vyzorix.audiorouter.common.logging.FileLogger
import com.vyzorix.audiorouter.common.logging.Logger
import java.util.concurrent.atomic.AtomicReference

/** Process-wide accessor for the daemon's [FileLogger]. */
public object DaemonLogger {

    private val installed: AtomicReference<Logger> = AtomicReference(ConsoleLogger())

    /**
     * Wire the daemon's persistent FileLogger. Safe to call more than once
     * — the second call replaces the previous logger.
     *
     * @param chunkSizeBytes Rolling chunk size. Per the no-ADB acceptance
     *   gate this defaults to 2 MiB so each .txt fits in a single MediaStore
     *   share.
     * @param maxRolledFiles Number of rolled snapshots to retain. 8 × 2 MiB =
     *   16 MiB worst case on disk, well below the Nokia C22's 32 GiB eMMC
     *   floor.
     */
    public fun install(
        context: Context,
        chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
        maxRolledFiles: Int = DEFAULT_MAX_ROLLED_FILES,
    ): Logger {
        val target = DaemonLogPaths.rollingLogFile(context)
        val fileLogger = FileLogger(
            file = target,
            maxFileSizeBytes = chunkSizeBytes,
            maxRolledFiles = maxRolledFiles,
        )
        installed.set(fileLogger)
        return fileLogger
    }

    /** Read-only handle on whichever logger is currently installed. */
    public fun get(): Logger = installed.get()

    public const val DEFAULT_CHUNK_SIZE_BYTES: Long = 2L * 1024L * 1024L
    public const val DEFAULT_MAX_ROLLED_FILES: Int = 8
}

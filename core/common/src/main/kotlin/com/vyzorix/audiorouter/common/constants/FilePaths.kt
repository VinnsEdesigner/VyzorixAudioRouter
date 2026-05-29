package com.vyzorix.audiorouter.common.constants

/**
 * Relative path segments for log files, exports, temp cache, and update APKs.
 *
 * These are relative to the app-internal storage root; the actual
 * File construction (context.filesDir / context.cacheDir) happens in Layer 1+.
 */
public object FilePaths {
    public const val DIR_LOGS: String = "logs"
    public const val DIR_CRASH_EXPORTS: String = "crash_exports"
    public const val DIR_TEMP: String = "temp"
    public const val DIR_UPDATES: String = "updates"
    public const val DIR_DIAGNOSTICS: String = "diagnostics"

    public const val FILE_ROLLING_LOG: String = "rolling.log"
    public const val FILE_FLIGHT_RECORDER: String = "flight_data.json"
    public const val FILE_CRASH_SNAPSHOT: String = "crash_snapshot.zip"
}

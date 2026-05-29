// DaemonLogPaths — canonical filesystem layout for the daemon's logs.
//
// The Layer-3.5 no-ADB workflow depends on having ONE well-known directory the
// FileLogger writes into, that the LogBundleExporter then zips. Living in
// :core:services (not :core:common) because it needs an Android Context to
// resolve [Context.filesDir].

package com.vyzorix.audiorouter.services.logging

import android.content.Context
import com.vyzorix.audiorouter.common.constants.FilePaths
import java.io.File

/** Resolves the absolute log paths used by the daemon. */
public object DaemonLogPaths {

    /** Absolute path of the directory containing rolling.log + .rolled snapshots. */
    public fun logDirectory(context: Context): File {
        return File(context.filesDir, FilePaths.DIR_LOGS).also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /** Path of the currently-live rolling log file. */
    public fun rollingLogFile(context: Context): File {
        return File(logDirectory(context), FilePaths.FILE_ROLLING_LOG)
    }
}

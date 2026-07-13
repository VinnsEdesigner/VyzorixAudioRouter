package com.vyzorix.audiorouter.services.crash

import java.io.File

public class NativeCrashMarker(private val markerDir: File) {
    public fun mark(signal: String, details: String = ""): File {
        markerDir.mkdirs()
        return File(markerDir, "native_${System.currentTimeMillis()}.marker").also {
            it.writeText("$signal\n$details")
        }
    }

    public fun scan(): List<File> = markerDir
        .listFiles { f -> f.name.startsWith("native_") && f.extension == "marker" }
        ?.toList()
        .orEmpty()
}

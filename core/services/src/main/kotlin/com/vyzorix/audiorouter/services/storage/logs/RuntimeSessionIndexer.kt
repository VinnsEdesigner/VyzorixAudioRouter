package com.vyzorix.audiorouter.services.storage.logs

import java.io.File

/** Allocates stable diagnostic session directories and indexes their active log. */
public class RuntimeSessionIndexer(private val rootDir: File) {
    public fun createSession(epochMs: Long = System.currentTimeMillis()): RuntimeSession {
        val id = "session_$epochMs"
        val directory = File(rootDir, id)
        directory.mkdirs()
        return RuntimeSession(id, directory, File(directory, CURRENT_LOG_NAME))
    }

    public fun latestSession(): RuntimeSession? = rootDir
        .listFiles { file -> file.isDirectory && file.name.startsWith("session_") }
        ?.maxByOrNull { it.name }
        ?.let { directory -> RuntimeSession(directory.name, directory, File(directory, CURRENT_LOG_NAME)) }

    public companion object {
        public const val CURRENT_LOG_NAME: String = "current_session.log"
    }
}

public data class RuntimeSession(
    public val id: String,
    public val directory: File,
    public val currentLog: File,
)

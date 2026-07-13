package com.vyzorix.audiorouter.services.storage

import com.vyzorix.audiorouter.services.diagnostics.DiagnosticEvent
import java.io.File

/** Append-only file-backed queue for diagnostic events that must survive crashes. */
public class PersistentEventQueue(private val file: File) {
    public fun offer(event: DiagnosticEvent): Unit = synchronized(this) {
        file.parentFile?.mkdirs()
        file.appendText(encode(event) + "\n")
    }

    public fun peekAll(): List<DiagnosticEvent> = synchronized(this) {
        if (!file.exists()) return@synchronized emptyList()
        file.readLines().mapNotNull { decode(it) }
    }

    public fun drain(): List<DiagnosticEvent> = synchronized(this) {
        val events = peekAll()
        if (file.exists()) file.writeText("")
        events
    }

    private fun encode(event: DiagnosticEvent): String = listOf(
        event.epochMs.toString(),
        escape(event.type),
        escape(event.message),
        event.attributes.toSortedMap().entries.joinToString("&") { (key, value) -> "${escape(key)}=${escape(value)}" },
    ).joinToString("|")

    private fun decode(line: String): DiagnosticEvent? {
        val parts = line.split('|', limit = 4)
        if (parts.size != 4) return null
        val attrs = if (parts[3].isBlank()) {
            emptyMap()
        } else {
            parts[3].split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null else unescape(pair.substring(0, idx)) to unescape(pair.substring(idx + 1))
            }.toMap()
        }
        return DiagnosticEvent(unescape(parts[1]), unescape(parts[2]), parts[0].toLongOrNull() ?: return null, attrs)
    }

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace("&", "%26")
        .replace("=", "%3D")
        .replace("\n", "%0A")

    private fun unescape(value: String): String = value
        .replace("%0A", "\n")
        .replace("%3D", "=")
        .replace("%26", "&")
        .replace("%7C", "|")
        .replace("%25", "%")
}

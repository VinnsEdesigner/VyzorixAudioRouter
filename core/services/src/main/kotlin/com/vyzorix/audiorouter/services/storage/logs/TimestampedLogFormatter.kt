package com.vyzorix.audiorouter.services.storage.logs

import com.vyzorix.audiorouter.services.diagnostics.DiagnosticEvent
import java.time.Instant

/** Formats one structured diagnostic event as a stable UTC trace line. */
public class TimestampedLogFormatter {
    public fun format(event: DiagnosticEvent): String {
        val attrs = event.attributes
            .toSortedMap()
            .entries
            .joinToString(separator = " ") { (key, value) -> "$key=${escape(value)}" }
        return buildString {
            append(Instant.ofEpochMilli(event.epochMs))
            append(" tid=")
            append(Thread.currentThread().id)
            append(" type=")
            append(event.type)
            append(" msg=")
            append(escape(event.message))
            if (attrs.isNotEmpty()) {
                append(' ')
                append(attrs)
            }
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace(" ", "%20")
}

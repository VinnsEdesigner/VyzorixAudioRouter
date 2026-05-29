package com.vyzorix.audiorouter.common.logging

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure-Kotlin formatter producing a single line of the form:
 *
 *   "2025-05-29T03:34:12.123Z I/Vyzorix/MyTag: hello world"
 *
 * The Throwable stack trace (if any) is appended on subsequent indented lines.
 * Used by ConsoleLogger and (eventually) FileLogger in Layer 6.
 */
public object TimestampedLogFormatter {

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    public fun format(
        epochMillis: Long,
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ): String {
        val ts = isoFormatter.format(Instant.ofEpochMilli(epochMillis))
        val head = "$ts ${level.shortCode()}/$tag: $message"
        if (throwable == null) return head
        val stack = throwable.stackTraceToString().trimEnd().prependIndent("    ")
        return "$head\n$stack"
    }

    private fun LogLevel.shortCode(): Char = when (this) {
        LogLevel.VERBOSE -> 'V'
        LogLevel.DEBUG -> 'D'
        LogLevel.INFO -> 'I'
        LogLevel.WARN -> 'W'
        LogLevel.ERROR -> 'E'
    }
}

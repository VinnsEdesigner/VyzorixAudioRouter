package com.vyzorix.audiorouter.services.diagnostics

import com.vyzorix.audiorouter.services.storage.logs.LogFileRotator
import com.vyzorix.audiorouter.services.storage.logs.RotationResult
import com.vyzorix.audiorouter.services.storage.logs.TimestampedLogFormatter
import java.io.File

/** Continuously writes diagnostic events to current_session.log and rotates it. */
public class RollingLogWriter(
    private val logFile: File,
    private val formatter: TimestampedLogFormatter = TimestampedLogFormatter(),
    private val rotator: LogFileRotator = LogFileRotator(),
) {
    public fun append(event: DiagnosticEvent): RotationResult {
        logFile.parentFile?.mkdirs()
        logFile.appendText(formatter.format(event) + "\n")
        return rotator.rotateIfNeeded(logFile)
    }

    public fun flush(events: Iterable<DiagnosticEvent>): List<RotationResult> = events.map { append(it) }
}

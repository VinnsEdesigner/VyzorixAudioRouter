package com.vyzorix.audiorouter.common.logging

import com.vyzorix.audiorouter.common.constants.AppConstants
import java.io.PrintStream

/**
 * Default [Logger] for Layer 0. Writes timestamped lines to stdout for
 * VERBOSE/DEBUG/INFO and stderr for WARN/ERROR — same convention BUILD_ORDER.md
 * specifies for the Layer 0 logging primitive ("logs go to println/stderr").
 *
 * Layer 6 replaces this with a Logcat-bridging implementation.
 *
 * @param tagPrefix prepended to every tag, defaults to "Vyzorix/".
 * @param now provides epoch millis; injected for deterministic tests.
 * @param stdout output stream for non-warning levels.
 * @param stderr output stream for warning / error levels.
 */
public class ConsoleLogger(
    private val tagPrefix: String = AppConstants.LOG_TAG_PREFIX,
    private val now: () -> Long = System::currentTimeMillis,
    private val stdout: PrintStream = System.out,
    private val stderr: PrintStream = System.err,
) : Logger {

    override fun verbose(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.VERBOSE, tag, message, throwable, stdout)
    }

    override fun debug(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.DEBUG, tag, message, throwable, stdout)
    }

    override fun info(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.INFO, tag, message, throwable, stdout)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.WARN, tag, message, throwable, stderr)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        emit(LogLevel.ERROR, tag, message, throwable, stderr)
    }

    private fun emit(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        sink: PrintStream,
    ) {
        val formatted = TimestampedLogFormatter.format(
            epochMillis = now(),
            level = level,
            tag = tagPrefix + tag,
            message = message,
            throwable = throwable,
        )
        sink.println(formatted)
    }
}

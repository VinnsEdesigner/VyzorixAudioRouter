package com.vyzorix.audiorouter.services.crash

import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.services.diagnostics.CrashTrace
import com.vyzorix.audiorouter.services.diagnostics.CrashTraceStore
import com.vyzorix.audiorouter.services.diagnostics.DiagnosticEvent
import com.vyzorix.audiorouter.services.diagnostics.LogStreamCollector
import com.vyzorix.audiorouter.services.diagnostics.RollingLogWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide uncaught exception handler for Layer 6 crash forensics.
 *
 * It performs only bounded, synchronous work that is safe in a crashing process:
 * classify, persist a short trace, write one panic event, flush any pending
 * diagnostic events, then delegate to the previous handler or halt.
 */
public class GlobalExceptionHandler(
    private val collector: LogStreamCollector,
    private val writer: RollingLogWriter,
    private val traceStore: CrashTraceStore,
    private val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
    private val haltProcess: (Int) -> Unit = { code -> Runtime.getRuntime().halt(code) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Thread.UncaughtExceptionHandler {
    private val handlingCrash: AtomicBoolean = AtomicBoolean(false)

    public fun install(): Unit {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable): Unit {
        if (!handlingCrash.compareAndSet(false, true)) {
            haltProcess(REENTRANT_HALT_CODE)
            return
        }

        val record = buildCrashRecord(thread, throwable)
        try {
            traceStore.record(CrashTrace(record.signature, record.stackHead, record.epochMs))
            collector.record(record.toEvent())
            writer.flush(collector.drain())
        } catch (_: Throwable) {
            haltProcess(LOGGING_FAILURE_HALT_CODE)
            return
        }

        previous?.uncaughtException(thread, throwable) ?: haltProcess(DEFAULT_HALT_CODE)
    }

    public fun buildCrashRecord(thread: Thread, throwable: Throwable): CrashPanicRecord {
        val stack = throwable.stackTraceString()
        return CrashPanicRecord(
            epochMs = clock(),
            threadName = thread.name,
            crashType = classify(throwable, stack),
            throwableClass = throwable.javaClass.name,
            message = throwable.message.orEmpty(),
            signature = signatureOf(throwable),
            stackHead = stack.take(MAX_STACK_HEAD_CHARS),
        )
    }

    public fun classify(throwable: Throwable, stack: String = throwable.stackTraceString()): CrashType {
        val haystack = throwable.javaClass.name + "\n" + throwable.message.orEmpty() + "\n" + stack
        return when {
            haystack.contains("SIGSEGV", ignoreCase = true) ||
                haystack.contains("SIGBUS", ignoreCase = true) ||
                haystack.contains("native", ignoreCase = true) -> CrashType.NATIVE_FAILURE
            haystack.contains("system_server", ignoreCase = true) ||
                haystack.contains("DeadSystemException", ignoreCase = true) -> CrashType.SYSTEM_DIED
            haystack.contains("Timeout", ignoreCase = true) ||
                haystack.contains("Watchdog", ignoreCase = true) -> CrashType.TIMEOUT
            else -> CrashType.APP_BUG
        }
    }

    private fun signatureOf(throwable: Throwable): String {
        val topFrame = throwable.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return listOf(throwable.javaClass.name, topFrame)
            .filter { it.isNotBlank() }
            .joinToString(separator = "@")
            .lowercase()
    }

    public companion object {
        public const val MAX_STACK_HEAD_CHARS: Int = 4096
        public const val DEFAULT_HALT_CODE: Int = -1
        public const val LOGGING_FAILURE_HALT_CODE: Int = -2
        public const val REENTRANT_HALT_CODE: Int = -3
    }
}

public data class CrashPanicRecord(
    public val epochMs: Long,
    public val threadName: String,
    public val crashType: CrashType,
    public val throwableClass: String,
    public val message: String,
    public val signature: String,
    public val stackHead: String,
) {
    public fun toEvent(): DiagnosticEvent = DiagnosticEvent(
        type = "panic",
        message = message,
        epochMs = epochMs,
        attributes = mapOf(
            "thread" to threadName,
            "crashType" to crashType.name,
            "throwable" to throwableClass,
            "signature" to signature,
            "stackHead" to stackHead,
        ),
    )
}

public fun Throwable.stackTraceString(): String = StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()

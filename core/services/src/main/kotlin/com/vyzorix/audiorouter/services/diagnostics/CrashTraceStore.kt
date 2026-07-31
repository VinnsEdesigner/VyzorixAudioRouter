package com.vyzorix.audiorouter.services.diagnostics

import java.util.ArrayDeque

/** Bounded index of recent crash signatures and stack heads. */
public class CrashTraceStore(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val traces: ArrayDeque<CrashTrace> = ArrayDeque(maxEntries)

    public fun record(trace: CrashTrace): Unit = synchronized(traces) {
        traces.addLast(trace)
        while (traces.size > maxEntries) traces.removeFirst()
    }

    public fun recent(limit: Int = maxEntries): List<CrashTrace> {
        return synchronized(traces) {
            traces.toList().takeLast(limit.coerceAtLeast(0))
        }
    }

    public fun countSince(epochMs: Long): Int = synchronized(traces) {
        traces.count { it.epochMs >= epochMs }
    }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 32
    }
}

public data class CrashTrace(
    public val signature: String,
    public val stackHead: String,
    public val epochMs: Long = System.currentTimeMillis(),
)

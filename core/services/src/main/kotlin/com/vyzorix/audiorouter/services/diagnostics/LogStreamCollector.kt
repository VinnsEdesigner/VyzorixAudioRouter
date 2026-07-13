package com.vyzorix.audiorouter.services.diagnostics

import java.util.ArrayDeque

/**
 * Bounded, thread-safe in-memory log bus for Layer 6 diagnostics.
 *
 * Producers append structured events from observers. [RollingLogWriter] drains
 * batches to disk. The bounded deque protects the Nokia C22 from unbounded RAM
 * growth while still preserving the newest forensic context.
 */
public class LogStreamCollector(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val entries: ArrayDeque<DiagnosticEvent> = ArrayDeque(maxEntries)
    private var droppedCount: Long = 0L

    public fun record(event: DiagnosticEvent): Unit = synchronized(entries) {
        entries.addLast(event)
        while (entries.size > maxEntries) {
            entries.removeFirst()
            droppedCount += 1L
        }
    }

    public fun record(
        type: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ): Unit = record(
        DiagnosticEvent(
            type = type,
            message = message,
            epochMs = clock(),
            attributes = attributes,
        ),
    )

    public fun drain(maxBatchSize: Int = maxEntries): List<DiagnosticEvent> = synchronized(entries) {
        val batchSize = minOf(maxBatchSize.coerceAtLeast(0), entries.size)
        buildList(batchSize) {
            repeat(batchSize) { add(entries.removeFirst()) }
        }
    }

    public fun snapshot(): LogStreamSnapshot = synchronized(entries) {
        LogStreamSnapshot(
            events = entries.toList(),
            droppedCount = droppedCount,
            capacity = maxEntries,
        )
    }

    public fun clear(): Unit = synchronized(entries) {
        entries.clear()
    }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 512
    }
}

public data class LogStreamSnapshot(
    public val events: List<DiagnosticEvent>,
    public val droppedCount: Long,
    public val capacity: Int,
)

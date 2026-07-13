package com.vyzorix.audiorouter.services.storage

import com.vyzorix.audiorouter.services.diagnostics.DiagnosticEvent

/** Lightweight checkpoint writer that bridges runtime state into the persistent queue. */
public class RuntimeCheckpointWriter(private val queue: PersistentEventQueue) {
    public fun checkpoint(name: String, attributes: Map<String, String>, epochMs: Long = System.currentTimeMillis()): Unit {
        queue.offer(
            DiagnosticEvent(
                type = "checkpoint",
                message = name,
                epochMs = epochMs,
                attributes = attributes,
            ),
        )
    }
}

package com.vyzorix.audiorouter.services.monitoring

/** Stateful monitor snapshot for HeadsetStateMonitor; Android listeners feed update() from wiring code. */
public class HeadsetStateMonitor {
    @Volatile
    public var active: Boolean = false
        private set

    @Volatile
    public var lastChangedAtMs: Long = 0L
        private set

    public fun update(value: Boolean, epochMs: Long = System.currentTimeMillis()): MonitorState {
        active = value
        lastChangedAtMs = epochMs
        return snapshot()
    }

    public fun snapshot(): MonitorState = MonitorState(active, lastChangedAtMs)
}

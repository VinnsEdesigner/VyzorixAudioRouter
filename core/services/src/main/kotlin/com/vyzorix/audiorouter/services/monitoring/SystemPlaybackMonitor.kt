package com.vyzorix.audiorouter.services.monitoring

/** Canonical Layer 6 playback monitor name from BUILD_ORDER; delegates state semantics to PlaybackStateMonitor. */
public class SystemPlaybackMonitor {
    private val delegate: PlaybackStateMonitor = PlaybackStateMonitor()

    public fun update(playing: Boolean, epochMs: Long = System.currentTimeMillis()): MonitorState = delegate.update(playing, epochMs)
    public fun snapshot(): MonitorState = delegate.snapshot()
}

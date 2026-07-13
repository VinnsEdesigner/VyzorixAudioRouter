package com.vyzorix.audiorouter.services.monitoring

public data class MonitorState(
    public val active: Boolean,
    public val lastChangedAtMs: Long,
)

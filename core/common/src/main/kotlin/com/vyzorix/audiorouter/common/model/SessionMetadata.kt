package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Per-diagnostic-session metadata bundle (boot count, timestamps, file counts). */
@Serializable
public data class SessionMetadata(
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("ended_at_ms") val endedAtMs: Long? = null,
    @SerialName("boot_count") val bootCount: Int,
    @SerialName("crash_count") val crashCount: Int = 0,
    @SerialName("log_file_count") val logFileCount: Int = 0,
    @SerialName("device_class") val deviceClass: String,
    @SerialName("app_version") val appVersion: String,
)

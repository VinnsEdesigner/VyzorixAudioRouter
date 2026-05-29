package com.vyzorix.audiorouter.common.model

import com.vyzorix.audiorouter.common.enums.CrashType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Structured fingerprint of a crash for diagnostic grouping / dashboard rendering. */
@Serializable
public data class CrashSignature(
    @SerialName("crash_type") val crashType: CrashType,
    @SerialName("class_name") val className: String?,
    @SerialName("top_frame") val topFrame: String?,
    @SerialName("message") val message: String?,
    @SerialName("occurred_at_ms") val occurredAtMs: Long,
    @SerialName("layer_hint") val layerHint: Int? = null,
)

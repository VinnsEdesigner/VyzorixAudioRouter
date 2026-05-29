package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Snapshot of the daemon's currently granted runtime permissions.
 * Layer 0 stores only strings + booleans so the model stays Android-free.
 */
@Serializable
public data class PermissionState(
    @SerialName("permission") val permission: String,
    @SerialName("granted") val granted: Boolean,
    @SerialName("user_dismissed") val userDismissed: Boolean = false,
    @SerialName("last_checked_at_ms") val lastCheckedAtMs: Long,
)

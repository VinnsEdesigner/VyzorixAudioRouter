package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/** Subtypes of AudioFocusLoss as forwarded to the daemon's focus listener. */
@Serializable
public enum class FocusLossType {
    /** Focus lost briefly (incoming notification, etc.) — retain capture. */
    TRANSIENT,

    /** Focus lost; ducking expected, no need to release. */
    TRANSIENT_CAN_DUCK,

    /** Focus lost permanently — release projection and pause capture. */
    PERMANENT,
}

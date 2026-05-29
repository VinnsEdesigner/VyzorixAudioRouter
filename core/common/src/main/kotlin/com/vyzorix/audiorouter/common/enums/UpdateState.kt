package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/** State machine of the OTA update flow — see UPDATE_MECHANISM.md. */
@Serializable
public enum class UpdateState {
    NOT_CHECKED,
    AVAILABLE,
    DOWNLOADING,
    DOWNLOADED,
    INSTALLING,
    SUCCESS,
    FAILED,
}

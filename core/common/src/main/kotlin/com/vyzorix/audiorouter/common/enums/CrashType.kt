package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/**
 * Classification of crashes observed by the diagnostic stack.
 *
 * See SOFT_REBOOT_ANALYSIS.md for the rationale of separating SYSTEM_DIED
 * (zygote-level / soft reboot) from APP_BUG.
 */
@Serializable
public enum class CrashType {
    /** Zygote / system_server died — see SOFT_REBOOT_ANALYSIS.md. */
    SYSTEM_DIED,

    /** Plain Kotlin/Java exception bubbled to GlobalExceptionHandler. */
    APP_BUG,

    /** Native (JNI) segfault / SIGSEGV / abort. */
    NATIVE_FAILURE,

    /** Watchdog timed-out waiting on a layer to make progress. */
    TIMEOUT,
}

package com.vyzorix.audiorouter.services.diagnostics.system

import com.vyzorix.audiorouter.services.diagnostics.DiagnosticEvent

/** Detects sub-500ms app windows as flash-crash evidence. */
public class WindowTransitionTracker(private val flashCrashMs: Long = DEFAULT_FLASH_CRASH_MS) {
    public fun classify(transition: WindowTransition): WindowTransitionClassification = when {
        transition.durationMs < 0L -> WindowTransitionClassification.Invalid
        transition.durationMs < flashCrashMs -> WindowTransitionClassification.FlashCrash
        else -> WindowTransitionClassification.Normal
    }

    public fun isFlashCrash(transition: WindowTransition): Boolean = classify(transition) == WindowTransitionClassification.FlashCrash

    public fun toEvent(transition: WindowTransition): DiagnosticEvent? = if (isFlashCrash(transition)) {
        DiagnosticEvent(
            type = "window_flash_crash",
            message = "window closed within ${transition.durationMs}ms",
            epochMs = transition.closedAtMs,
            attributes = mapOf(
                "package" to transition.packageName,
                "durationMs" to transition.durationMs.toString(),
            ),
        )
    } else {
        null
    }

    public companion object {
        public const val DEFAULT_FLASH_CRASH_MS: Long = 500L
    }
}

public data class WindowTransition(
    public val packageName: String,
    public val openedAtMs: Long,
    public val closedAtMs: Long,
) {
    public val durationMs: Long get() = closedAtMs - openedAtMs
}

public enum class WindowTransitionClassification {
    Normal,
    FlashCrash,
    Invalid,
}

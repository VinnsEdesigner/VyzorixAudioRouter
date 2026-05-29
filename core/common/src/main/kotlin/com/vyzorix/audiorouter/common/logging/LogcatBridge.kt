package com.vyzorix.audiorouter.common.logging

import android.util.Log
import com.vyzorix.audiorouter.common.constants.AppConstants

/**
 * Lightweight [Logger] adapter that forwards calls to `android.util.Log`.
 *
 * Used in production by Layer 6+ as the default sink for the in-process
 * logger. Robolectric unit tests in Layer 0 use [ConsoleLogger] instead
 * because that doesn't pull `android.util.Log` (which has a Robolectric
 * shadow but emits stderr noise that breaks test-output parsing).
 *
 * Tag handling:
 *   - The Android log API caps tags at 23 characters on API < 26. We
 *     defensively clamp every tag (after prefixing) to that limit so the
 *     same source code logs the same way across the OEM build matrix.
 */
public class LogcatBridge(
    private val tagPrefix: String = AppConstants.LOG_TAG_PREFIX,
) : Logger {

    override fun verbose(tag: String, message: String, throwable: Throwable?) {
        val full = clamp(tagPrefix + tag)
        if (throwable != null) Log.v(full, message, throwable) else Log.v(full, message)
    }

    override fun debug(tag: String, message: String, throwable: Throwable?) {
        val full = clamp(tagPrefix + tag)
        if (throwable != null) Log.d(full, message, throwable) else Log.d(full, message)
    }

    override fun info(tag: String, message: String, throwable: Throwable?) {
        val full = clamp(tagPrefix + tag)
        if (throwable != null) Log.i(full, message, throwable) else Log.i(full, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        val full = clamp(tagPrefix + tag)
        if (throwable != null) Log.w(full, message, throwable) else Log.w(full, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        val full = clamp(tagPrefix + tag)
        if (throwable != null) Log.e(full, message, throwable) else Log.e(full, message)
    }

    private fun clamp(tag: String): String =
        if (tag.length <= MAX_TAG_LENGTH) tag else tag.substring(0, MAX_TAG_LENGTH)

    public companion object {
        /** API < 26 limit; harmless on newer Androids. */
        public const val MAX_TAG_LENGTH: Int = 23
    }
}

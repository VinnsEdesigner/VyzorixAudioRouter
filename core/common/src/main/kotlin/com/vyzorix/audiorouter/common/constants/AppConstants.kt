package com.vyzorix.audiorouter.common.constants

/**
 * Global, version-agnostic identifiers used across the daemon.
 *
 * These are deliberately string/numeric primitives — no Android types — so this
 * file compiles in pure Kotlin Layer 0 per doc/BUILD_ORDER.md.
 */
public object AppConstants {
    public const val APPLICATION_ID: String = "com.vyzorix.audiorouter"
    public const val APP_NAME: String = "Vyzorix Audio Router"

    /** Tag prefix used by Logger implementations. Real Android Log.* binding is Layer 6+. */
    public const val LOG_TAG_PREFIX: String = "Vyzorix/"

    /** Default device class advertised during /v1/device/register when running on a C22. */
    public const val DEVICE_CLASS_NOKIA_C22: String = "nokia_c22"

    /** Fallback device class when no profile matches. */
    public const val DEVICE_CLASS_UNKNOWN: String = "unknown_device"
}

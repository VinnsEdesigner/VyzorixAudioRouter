package com.vyzorix.audiorouter.common.constants

/**
 * Runtime permission strings and request codes.
 *
 * Stored as plain strings (not android.Manifest.permission references) so
 * Layer 0 stays pure-Kotlin. Layer 3+ can compare against the system manifest.
 */
public object PermissionConstants {
    public const val RECORD_AUDIO: String = "android.permission.RECORD_AUDIO"
    public const val FOREGROUND_SERVICE: String = "android.permission.FOREGROUND_SERVICE"
    public const val FOREGROUND_SERVICE_MEDIA_PROJECTION: String =
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"
    public const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"
    public const val REQUEST_INSTALL_PACKAGES: String =
        "android.permission.REQUEST_INSTALL_PACKAGES"
    public const val SYSTEM_ALERT_WINDOW: String = "android.permission.SYSTEM_ALERT_WINDOW"
    public const val RECEIVE_BOOT_COMPLETED: String = "android.permission.RECEIVE_BOOT_COMPLETED"
    public const val WAKE_LOCK: String = "android.permission.WAKE_LOCK"
    public const val INTERNET: String = "android.permission.INTERNET"
    public const val ACCESS_NETWORK_STATE: String = "android.permission.ACCESS_NETWORK_STATE"

    public const val REQUEST_CODE_PROJECTION: Int = 2001
    public const val REQUEST_CODE_PERMISSIONS: Int = 2002
}

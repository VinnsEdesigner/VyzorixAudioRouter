package com.vyzorix.audiorouter.common.constants

/** SharedPreferences key definitions used across modules. */
public object PrefKeys {
    public const val PREFS_NAME: String = "vyzorix_prefs"

    public const val DAEMON_STATE: String = "daemon_state"
    public const val ROUTE_STATE: String = "route_state"
    public const val CAPTURE_STATE: String = "capture_state"
    public const val SAFE_MODE_ENABLED: String = "safe_mode_enabled"

    public const val DEVICE_ID: String = "device_id"
    public const val FIREBASE_INSTALL_ID: String = "firebase_install_id"
    public const val FCM_TOKEN: String = "fcm_token"
    public const val REGISTERED_AT: String = "registered_at"

    public const val LAST_UPDATE_CHECK: String = "last_update_check"
    public const val LAST_KNOWN_VERSION_CODE: String = "last_known_version_code"
    public const val UPDATE_DOWNLOAD_PATH: String = "update_download_path"

    public const val BOOT_COUNT: String = "boot_count"
    public const val LAST_CRASH_EPOCH: String = "last_crash_epoch"
    public const val CONSECUTIVE_CRASHES: String = "consecutive_crashes"
}

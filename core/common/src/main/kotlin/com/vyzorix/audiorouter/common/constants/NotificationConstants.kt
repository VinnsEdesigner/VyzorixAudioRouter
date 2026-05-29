package com.vyzorix.audiorouter.common.constants

/** Notification channel IDs and notification IDs for the daemon's foreground service and dashboard. */
public object NotificationConstants {
    public const val CHANNEL_DAEMON: String = "vyzorix_daemon"
    public const val CHANNEL_UPDATE: String = "vyzorix_update"
    public const val CHANNEL_ALERT: String = "vyzorix_alert"

    public const val NOTIFICATION_ID_DAEMON: Int = 1001
    public const val NOTIFICATION_ID_DASHBOARD: Int = 1002
    public const val NOTIFICATION_ID_UPDATE: Int = 1003
    public const val NOTIFICATION_ID_CRASH_ALERT: Int = 1004
}

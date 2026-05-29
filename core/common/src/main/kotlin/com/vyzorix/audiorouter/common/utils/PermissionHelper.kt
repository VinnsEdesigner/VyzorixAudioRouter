// PermissionHelper — runtime permission introspection.
//
// Wraps Context.checkSelfPermission() so calls into the daemon's hot path
// don't have to discriminate between the legacy and `ContextCompat`-flavoured
// APIs. Pure read-only — actual permission requests are driven by the
// trampoline activities in `ui/`.

package com.vyzorix.audiorouter.common.utils

import android.content.Context
import android.content.pm.PackageManager
import com.vyzorix.audiorouter.common.constants.PermissionConstants

/** Read-only helpers for checking runtime permission grant state. */
public object PermissionHelper {

    /**
     * @return `true` iff the calling process holds the named permission.
     */
    public fun hasPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Convenience: `RECORD_AUDIO`. */
    public fun canRecordAudio(context: Context): Boolean =
        hasPermission(context, PermissionConstants.RECORD_AUDIO)

    /** Convenience: `POST_NOTIFICATIONS` (A13+ mandatory). */
    public fun canPostNotifications(context: Context): Boolean =
        hasPermission(context, PermissionConstants.POST_NOTIFICATIONS)

    /** Convenience: `SYSTEM_ALERT_WINDOW` — overlay permission. */
    public fun hasOverlayPermission(context: Context): Boolean =
        hasPermission(context, PermissionConstants.SYSTEM_ALERT_WINDOW)

    /** Convenience: `INTERNET` + `ACCESS_NETWORK_STATE` both granted. */
    public fun hasNetworkPermissions(context: Context): Boolean =
        hasPermission(context, PermissionConstants.INTERNET) &&
            hasPermission(context, PermissionConstants.ACCESS_NETWORK_STATE)

    /**
     * @return a map from permission name to grant state for the canonical
     * permission set the daemon requires.
     */
    public fun snapshot(context: Context): Map<String, Boolean> = listOf(
        PermissionConstants.RECORD_AUDIO,
        PermissionConstants.POST_NOTIFICATIONS,
        PermissionConstants.FOREGROUND_SERVICE,
        PermissionConstants.FOREGROUND_SERVICE_MEDIA_PROJECTION,
        PermissionConstants.REQUEST_INSTALL_PACKAGES,
        PermissionConstants.SYSTEM_ALERT_WINDOW,
        PermissionConstants.WAKE_LOCK,
        PermissionConstants.INTERNET,
        PermissionConstants.ACCESS_NETWORK_STATE,
        PermissionConstants.RECEIVE_BOOT_COMPLETED,
    ).associateWith { hasPermission(context, it) }
}

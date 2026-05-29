// PermissionIntentHelper — centralised PendingIntent construction for
// permission-related flows (settings deep-links, "request again" actions,
// projection re-grant prompts).
//
// Lives here so every PendingIntent passed to a NotificationCompat.Action
// or Settings deep-link uses the same FLAG_IMMUTABLE / FLAG_MUTABLE policy.
// A12+ enforces non-zero mutability flags — a missing flag is a crash.

package com.vyzorix.audiorouter.common.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.vyzorix.audiorouter.common.constants.PermissionConstants

/**
 * Builds PendingIntents for permission-management flows.
 *
 * All returned intents are immutable; the receiver (Settings activity) does
 * not need to mutate them and immutability is the safer default for any
 * pendingintent escaping the daemon process.
 */
public object PermissionIntentHelper {

    /**
     * Deep-link to the app's permission settings page in the system Settings
     * app. Always immutable.
     */
    public fun openAppPermissionsPendingIntent(
        context: Context,
        requestCode: Int = PermissionConstants.REQUEST_CODE_PERMISSIONS,
    ): PendingIntent {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Deep-link to the system Accessibility Settings page. Used by
     * `BootstrapActivity` to send the user to the page where they grant
     * the daemon its accessibility service.
     */
    public fun openAccessibilitySettingsPendingIntent(
        context: Context,
        requestCode: Int = PermissionConstants.REQUEST_CODE_PERMISSIONS,
    ): PendingIntent {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Deep-link to the "Display over other apps" Settings page. Required
     * for the daemon's permission-overlay flow.
     */
    public fun openOverlayPermissionPendingIntent(
        context: Context,
        requestCode: Int = PermissionConstants.REQUEST_CODE_PERMISSIONS,
    ): PendingIntent {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

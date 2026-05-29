package com.vyzorix.audiorouter.common.extensions

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * Defensive `Context` helpers shared across the daemon.
 *
 * Every helper here is annotated with what failure modes it absorbs and what
 * it propagates — defensive ≠ silent. The intent is to keep the
 * already-error-prone Android-API call sites readable in Layer 3+ code.
 */

/**
 * Returns the system service of type [T] or `null` if the service is
 * unavailable on this device (e.g. older Android versions). Wraps the
 * `Context.getSystemService(Class)` overload that requires API 23+ but is
 * safe under our `minSdk = 33`.
 */
public inline fun <reified T : Any> Context.safeGetSystemService(): T? = runCatching {
    getSystemService(T::class.java)
}.getOrNull()

/**
 * Calls `Service.startForeground` with the supplied notification, swallowing
 * the SecurityException Android can throw if the foreground-service-type
 * permission is missing in the manifest at boot time.
 *
 * Returns `true` on success, `false` if the OS rejected the foreground
 * transition. Callers MUST handle `false` by surfacing the failure to the
 * daemon's recovery ladder rather than silently continuing — see
 * `doc/DOC_4` recovery ladder §3.
 *
 * The `foregroundServiceType` flag is required on API 34+; pass `0` to
 * use the manifest declaration.
 */
@Suppress("NewApi") // minSdk = 33; the API 34 type-flag is guarded below.
public fun Service.safeStartForeground(
    notificationId: Int,
    notification: Notification,
    foregroundServiceType: Int = 0,
    logTag: String = "Service",
): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && foregroundServiceType != 0) {
        startForeground(notificationId, notification, foregroundServiceType)
    } else {
        startForeground(notificationId, notification)
    }
    true
} catch (se: SecurityException) {
    Log.w(logTag, "startForeground denied by OS", se)
    false
} catch (ise: IllegalStateException) {
    // App in cached / restricted background state — also a recovery signal.
    Log.w(logTag, "startForeground rejected (background restriction)", ise)
    false
}

/**
 * `true` when the calling thread is the main thread. Used by debug-only
 * assertions in higher layers to keep Room/DataStore reads off the main
 * thread (see [com.vyzorix.audiorouter.data.dao.DaemonStateDao] docs).
 */
public fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

/**
 * `foregroundServiceType` value that matches every type the manifest
 * declares for the daemon's foreground service — currently MEDIA_PROJECTION
 * + MICROPHONE. Exposed here so service callers don't have to import
 * `android.content.pm.ServiceInfo` directly.
 */
public val FOREGROUND_SERVICE_TYPE_DAEMON: Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

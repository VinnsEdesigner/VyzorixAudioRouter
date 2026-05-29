package com.vyzorix.audiorouter.common.extensions

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.RequiresPermission

/**
 * Connectivity helpers used by Layer 7+ network code.
 *
 * The daemon does not hold a live `NetworkCallback` here — that's the
 * responsibility of Layer 7's `NetworkAvailabilityWatcher`. These helpers
 * are point-in-time probes only.
 */

/** Tri-state network transport descriptor. */
public enum class NetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
}

private fun Context.connectivityManagerOrNull(): ConnectivityManager? =
    safeGetSystemService<ConnectivityManager>()

@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
private fun ConnectivityManager.activeCapabilities(): NetworkCapabilities? {
    val net = activeNetwork ?: return null
    return getNetworkCapabilities(net)
}

/**
 * Returns `true` when the system reports an active network with at least
 * NET_CAPABILITY_INTERNET. Does NOT validate that the network is reachable
 * (the validated flag is a stronger guarantee but only reliable on API
 * 23+; the daemon's `minSdk = 33` so the validated check is also available
 * — see [isInternetValidated]).
 */
@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
public fun Context.isConnected(): Boolean {
    val caps = connectivityManagerOrNull()?.activeCapabilities() ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Returns `true` when the active network has been validated by the OS
 * (i.e. the platform's captive-portal probe succeeded). Stricter than
 * [isConnected]; preferred for the C2 connect path.
 */
@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
public fun Context.isInternetValidated(): Boolean {
    val caps = connectivityManagerOrNull()?.activeCapabilities() ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Returns `true` when the active connection is metered (cellular / paid
 * Wi-Fi hotspot). Used by `UpdateDownloader` (Layer 7) to refuse OTA
 * downloads on metered links unless the operator explicitly opted in.
 */
@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
public fun Context.isMetered(): Boolean {
    val caps = connectivityManagerOrNull()?.activeCapabilities() ?: return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/** Returns the coarse transport type of the active network. */
@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
public fun Context.getActiveNetworkType(): NetworkTransport {
    val caps = connectivityManagerOrNull()?.activeCapabilities() ?: return NetworkTransport.NONE
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
        else -> NetworkTransport.OTHER
    }
}

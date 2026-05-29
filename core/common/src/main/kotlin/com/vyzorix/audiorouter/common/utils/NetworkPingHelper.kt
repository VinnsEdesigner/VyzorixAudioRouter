// NetworkPingHelper — TCP-level reachability check.
//
// Wraps a raw socket connection to a known-DNS target (default 8.8.8.8:53)
// with a bounded timeout. Used by Layer 5+'s `NetworkStateMonitor` before
// firing an update check — `ConnectivityManager.getNetworkCapabilities()`
// can report `NET_CAPABILITY_VALIDATED` while the daemon's path to the
// update server is in fact unreachable (captive portals, NAT-limited
// hotspots).

package com.vyzorix.audiorouter.common.utils

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** TCP reachability check helpers. */
public object NetworkPingHelper {

    public const val DEFAULT_HOST: String = "8.8.8.8"
    public const val DEFAULT_PORT: Int = 53
    public const val DEFAULT_TIMEOUT_MS: Int = 1_500

    /**
     * Attempt a TCP connect to [host]:[port] with [timeoutMillis] timeout.
     * Returns `true` iff the socket was opened successfully. Errors are
     * caught and translated to `false` — never thrown.
     *
     * Caller controls whether the result should be cached and for how long.
     */
    public fun ping(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be > 0: $timeoutMillis" }
        require(port in 1..65_535) { "port out of range: $port" }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.isConnected
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            // Missing INTERNET permission — treat as offline.
            false
        }
    }
}

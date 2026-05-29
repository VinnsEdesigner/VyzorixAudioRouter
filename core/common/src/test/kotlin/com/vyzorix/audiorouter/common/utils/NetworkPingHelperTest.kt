package com.vyzorix.audiorouter.common.utils

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NetworkPingHelperTest {

    @Test
    fun `ping returns false against an unreachable port quickly`() {
        // 169.254.x.x is link-local — extremely unlikely to be reachable from
        // the test environment. Timeout caps the call at the requested ms.
        val started = System.nanoTime()
        val reachable = NetworkPingHelper.ping(
            host = "169.254.42.42",
            port = 65_001,
            timeoutMillis = 300,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertFalse(reachable)
        // Allow generous slack for Robolectric / JVM overhead, but the call
        // must respect the timeout — fail if we burnt > 5s on a 300 ms ping.
        assert(elapsedMs < 5_000L) { "ping took ${elapsedMs}ms, expected < 5000ms" }
    }

    @Test
    fun `ping rejects non-positive timeouts`() {
        assertFailsWith<IllegalArgumentException> {
            NetworkPingHelper.ping(timeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkPingHelper.ping(timeoutMillis = -1)
        }
    }

    @Test
    fun `ping rejects out-of-range ports`() {
        assertFailsWith<IllegalArgumentException> {
            NetworkPingHelper.ping(port = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkPingHelper.ping(port = 65_536)
        }
    }
}

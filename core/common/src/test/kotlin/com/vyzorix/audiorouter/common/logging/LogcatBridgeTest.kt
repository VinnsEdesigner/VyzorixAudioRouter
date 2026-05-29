package com.vyzorix.audiorouter.common.logging

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogcatBridgeTest {

    @Test
    fun bridge_does_not_throw_for_any_level() {
        val bridge = LogcatBridge()
        bridge.verbose("tag", "v")
        bridge.debug("tag", "d")
        bridge.info("tag", "i")
        bridge.warn("tag", "w", IllegalStateException("warn"))
        bridge.error("tag", "e", IllegalStateException("err"))
    }

    @Test
    fun max_tag_length_constant_matches_pre_oreo_limit() {
        // The android.util.Log tag cap on API < 26 is 23 chars; the bridge
        // must clamp to that value so logs render the same across the matrix.
        assertEquals(23, LogcatBridge.MAX_TAG_LENGTH)
    }
}

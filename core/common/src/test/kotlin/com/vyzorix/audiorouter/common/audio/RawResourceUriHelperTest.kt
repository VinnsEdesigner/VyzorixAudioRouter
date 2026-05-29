package com.vyzorix.audiorouter.common.audio

import android.content.ContentResolver
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RawResourceUriHelperTest {

    @Test
    fun for_resource_builds_canonical_uri() {
        val uri = RawResourceUriHelper.forResource("com.vyzorix.audiorouter", 0x7f0a0001)
        assertEquals(ContentResolver.SCHEME_ANDROID_RESOURCE, uri.scheme)
        assertEquals("com.vyzorix.audiorouter", uri.authority)
        assertEquals("/2131361793", uri.path)
    }
}

package com.vyzorix.audiorouter.audioengine

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeLoaderTest {

    @After
    fun resetLoader() {
        NativeLoader.resetForTests()
        NativeAudioBridge.resetForTests()
    }

    @Test
    fun `ensureLoaded fails cleanly under Robolectric (no native library)`() {
        // Robolectric does not ship libaudioengine.so for the test JVM, so
        // the loader must surface a `Failed` snapshot rather than killing
        // the process with an UnsatisfiedLinkError. This is the same path a
        // device with a mismatched ABI would take.
        val loaded = NativeLoader.ensureLoaded()
        assertFalse(loaded, "Native library should be unavailable in Robolectric tests")
        val snapshot = NativeLoader.snapshot()
        assertTrue(
            snapshot is NativeLoader.LoadState.Failed,
            "Expected LoadState.Failed but was $snapshot",
        )
    }

    @Test
    fun `ensureLoaded is idempotent on failure`() {
        // After the first call records a failure, repeated calls MUST
        // return the cached state without re-invoking System.loadLibrary.
        val first = NativeLoader.ensureLoaded()
        val second = NativeLoader.ensureLoaded()
        assertFalse(first)
        assertFalse(second)
        val snapshot = NativeLoader.snapshot()
        assertTrue(snapshot is NativeLoader.LoadState.Failed)
    }

    @Test
    fun `failed-state snapshot retains the underlying throwable`() {
        NativeLoader.ensureLoaded()
        val snapshot = NativeLoader.snapshot() as NativeLoader.LoadState.Failed
        assertNotNull(snapshot.cause, "Failed snapshot must carry the underlying throwable")
        assertTrue(snapshot.reason.isNotBlank(), "Failed snapshot must carry a non-blank reason")
    }

    @Test
    fun `resetForTests returns the loader to NotAttempted`() {
        NativeLoader.ensureLoaded()
        NativeLoader.resetForTests()
        assertTrue(
            NativeLoader.snapshot() is NativeLoader.LoadState.NotAttempted,
            "resetForTests should clear the cached load state",
        )
    }
}

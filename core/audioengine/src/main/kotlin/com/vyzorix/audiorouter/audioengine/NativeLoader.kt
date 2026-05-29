// Defensive native-library loader.
//
// Per `doc/SYSTEM_MAP.md` §5 failure matrix:
//   - `UnsatisfiedLinkError` is the expected outcome on a device where the
//     ABI we built does not match the device's primary 32/64-bit slot.
//   - `LinkageError` (broader) appears when the loader pulls in a partially-
//     stripped shared object — observed historically on Unisoc OEM builds.
//
// In both cases the engine must report "native unavailable" instead of
// killing the process — Layer 6+ will fall back to `audio_fallback_bridge`
// (Java-only mixing path) if the native bridge is unusable.

package com.vyzorix.audiorouter.audioengine

import android.util.Log

/**
 * Loads the `libaudioengine.so` shared object using the JVM's
 * `System.loadLibrary` mechanism and tracks whether the load succeeded.
 *
 * The loader is idempotent: a successful first call short-circuits all
 * subsequent calls. A failed load is also recorded — once the loader has
 * decided the library is unavailable, subsequent calls will not retry
 * (the JVM caches the failure in `ClassLoader` regardless, but recording
 * it here lets the engine produce a clean `Result.Unavailable` instead of
 * re-raising the underlying `UnsatisfiedLinkError`).
 */
public object NativeLoader {

    private const val TAG: String = "VyzorixAudio.Loader"
    private const val LIBRARY_NAME: String = "audioengine"

    @Volatile
    private var state: LoadState = LoadState.NotAttempted

    /** Outcome of the most recent load attempt. */
    public sealed interface LoadState {
        public object NotAttempted : LoadState
        public object Loaded : LoadState
        public data class Failed(public val reason: String, public val cause: Throwable) : LoadState
    }

    /**
     * Attempt to load the native library. Idempotent — returns the cached
     * outcome on subsequent calls.
     *
     * @return `true` if the library is loaded, `false` if the engine should
     *         fall back to the Java-only audio path.
     */
    public fun ensureLoaded(): Boolean {
        when (state) {
            is LoadState.Loaded -> return true
            is LoadState.Failed -> return false
            is LoadState.NotAttempted -> Unit
        }
        synchronized(this) {
            val current = state
            if (current is LoadState.Loaded) return true
            if (current is LoadState.Failed) return false
            state = try {
                System.loadLibrary(LIBRARY_NAME)
                Log.i(TAG, "Loaded $LIBRARY_NAME")
                LoadState.Loaded
            } catch (linkError: UnsatisfiedLinkError) {
                Log.w(TAG, "UnsatisfiedLinkError loading $LIBRARY_NAME: ${linkError.message}")
                LoadState.Failed(reason = "UnsatisfiedLinkError", cause = linkError)
            } catch (linkage: LinkageError) {
                Log.w(TAG, "LinkageError loading $LIBRARY_NAME: ${linkage.message}")
                LoadState.Failed(reason = "LinkageError", cause = linkage)
            } catch (security: SecurityException) {
                // Some MDM-locked Android variants block `System.loadLibrary`
                // for any non-system-signed APK. Surface this distinctly so
                // Layer 6's `RuntimeEventTimeline` can record it.
                Log.w(TAG, "SecurityException loading $LIBRARY_NAME: ${security.message}")
                LoadState.Failed(reason = "SecurityException", cause = security)
            }
            return state is LoadState.Loaded
        }
    }

    /** Diagnostic accessor — exposes the load outcome without triggering a load. */
    public fun snapshot(): LoadState = state

    /**
     * Test-only seam — resets the loader so a hermetic unit test can simulate
     * the "not yet attempted" state. Not exposed to consumers.
     */
    internal fun resetForTests() {
        synchronized(this) { state = LoadState.NotAttempted }
    }
}

// MediaProjectionSession — owns the live `MediaProjection` Android object.
//
// Lifecycle:
//   1. Trampoline activity obtains the (resultCode, data) pair from the
//      system dialog and forwards it to PersistentAudioService.
//   2. Service calls [acquire(resultCode, data)] on this class, which
//      converts the Intent into a `MediaProjection` instance via
//      `MediaProjectionManager.getMediaProjection`.
//   3. [acquire] registers BOTH the [ProjectionDeathHandler] (for the
//      involuntary onStop callback) AND a one-shot lifecycle observer
//      for the voluntary stop path.
//   4. Callers retrieve the live `MediaProjection` via [activeProjection].
//   5. On [release], we call `MediaProjection.stop()` and detach
//      callbacks. The session is single-shot — after release the
//      caller must call [acquire] again with a fresh token.
//
// Why this exists (vs callers using MediaProjectionManager directly):
//   - MediaProjection.Callback is an Android abstract class — registering
//     it from each caller would duplicate the boilerplate.
//   - We need a single audit point for "where did the live projection go?"
//     because Layer 4's CaptureRecoveryEngine has to be able to ask
//     "is the projection still alive?" without poking AudioRecord
//     internals.
//   - Tests inject a fake `MediaProjectionManager` so the lifecycle
//     transitions can be exercised without an actual system dialog.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.1 +
// doc/MEDIA_PROJECTION_FLOW.md §Mitigation 3.

package com.vyzorix.audiorouter.services.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [MediaProjectionSession.acquire]. */
public sealed interface ProjectionAcquireResult {
    public data class Success(public val projection: MediaProjection) : ProjectionAcquireResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : ProjectionAcquireResult
}

/**
 * Owner of the live `MediaProjection`. Single-instance per service; the
 * service holds it for the daemon's process lifetime.
 *
 * Threading: methods are safe to call from any thread. Callbacks fired
 * by the Android framework arrive on the main thread (we use a main
 * looper Handler when registering the callback so the framework does
 * not crash).
 */
public class MediaProjectionSession(
    private val context: Context,
    private val deathHandler: ProjectionDeathHandler,
    private val projectionManager: MediaProjectionManager? = null,
) {

    private val activeRef: AtomicReference<MediaProjection?> = AtomicReference(null)
    private val callbackRef: AtomicReference<MediaProjection.Callback?> = AtomicReference(null)

    /** The live `MediaProjection`, or null when no active session. */
    public val activeProjection: MediaProjection?
        get() = activeRef.get()

    /** True iff a live `MediaProjection` is currently held. */
    public val isActive: Boolean
        get() = activeProjection != null

    /**
     * Acquire a fresh `MediaProjection` from the supplied (resultCode, data)
     * pair returned by [ProjectionPermissionActivity].
     */
    public fun acquire(resultCode: Int, data: Intent): ProjectionAcquireResult {
        val previous = activeRef.get()
        if (previous != null) {
            // Defensive: release the old one before we replace it.
            DaemonLogger.get().warn(TAG, "session.acquire.replaced_existing")
            releaseInternal(previous)
        }
        val manager = resolveManager()
            ?: return ProjectionAcquireResult.Failed("media_projection_manager_unavailable")

        val projection: MediaProjection? = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "session.acquire.failed err=${t.javaClass.simpleName} msg=${t.message}",
            )
            return ProjectionAcquireResult.Failed(
                reason = "get_media_projection_threw",
                cause = t,
            )
        }
        if (projection == null) {
            DaemonLogger.get().error(TAG, "session.acquire.failed reason=null_projection")
            return ProjectionAcquireResult.Failed("get_media_projection_returned_null")
        }
        val handlerThread = Handler(Looper.getMainLooper())
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                DaemonLogger.get().warn(TAG, "session.callback.onStop")
                deathHandler.onProjectionStopped()
                // Active ref is cleared so subsequent acquire()s start clean.
                activeRef.compareAndSet(projection, null)
                callbackRef.compareAndSet(this, null)
            }
        }
        try {
            projection.registerCallback(callback, handlerThread)
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "session.acquire.failed phase=register_callback err=${t.javaClass.simpleName} msg=${t.message}",
            )
            runCatching { projection.stop() }
            return ProjectionAcquireResult.Failed(
                reason = "register_callback_threw",
                cause = t,
            )
        }
        activeRef.set(projection)
        callbackRef.set(callback)
        DaemonLogger.get().info(TAG, "session.acquire.success")
        return ProjectionAcquireResult.Success(projection = projection)
    }

    /** Tear down the active projection. Safe to call when no active session. */
    public fun release() {
        val active = activeRef.getAndSet(null) ?: return
        releaseInternal(active)
    }

    private fun releaseInternal(projection: MediaProjection) {
        val callback = callbackRef.getAndSet(null)
        if (callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "session.release.unregister_failed err=${t.javaClass.simpleName}",
                )
            }
        }
        try {
            projection.stop()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "session.release.stop_failed err=${t.javaClass.simpleName}",
            )
        }
        DaemonLogger.get().info(TAG, "session.release.complete")
    }

    private fun resolveManager(): MediaProjectionManager? {
        return projectionManager
            ?: context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    private companion object {
        const val TAG: String = "MediaProjectionSession"
    }
}

// AudioTrackController — typed lifecycle facade around a single
// `android.media.AudioTrack` instance.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 635:
//     core/services/playback/AudioTrackController.kt
//       "play, pause, flush on physical AudioTrack instance".
//
// Why a separate class (vs inlining track.play()/track.stop() inside
// SpeakerPlaybackEngine):
//   1. The dashboard's "Restart pipeline" action (Layer 5 — see
//      RestartPipelineAction) needs to flush and resume the AudioTrack
//      without tearing down the engine's coroutine loop. The controller
//      exposes flush/pause/play as discrete operations the engine itself
//      cannot expose without leaking its private track reference.
//   2. RouteRecoveryEngine (this layer) needs to release-and-rebuild the
//      AudioTrack on `RouteState.HEADSET_HIJACK` without losing the queued
//      PCM frames. Owning the track lifecycle in one named class lets the
//      recovery engine reason about a single owner.
//   3. The PlaybackGainController writes to the same track; concentrating
//      every track-side mutation in this class keeps the synchronization
//      story trivial (atomic reference + per-op try/catch).
//
// All public methods are no-ops if no track is mounted. None throw on
// state-machine violations — AudioTrack's own error-state semantics are
// pinned through a try/catch wrapper because the OS occasionally throws
// IllegalStateException on the C22 when the HAL is mid-route-switch.
//
// Threading: every operation is callable from any thread. The underlying
// `AudioTrack` API is itself thread-safe for the methods exposed here
// (play/pause/flush/setVolume), but we serialize state transitions via
// the AtomicReference swap so a release() racing with a play() never
// leaves a dangling track.

package com.vyzorix.audiorouter.services.playback

import android.media.AudioTrack
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [AudioTrackController.mount]. */
public sealed interface MountResult {
    public object Mounted : MountResult
    public data class AlreadyMounted(public val previousFramesWritten: Long) : MountResult
    public data class Rejected(public val reason: String) : MountResult
}

/** Outcome of [AudioTrackController.write]. */
public sealed interface ControllerWriteResult {
    public data class Wrote(public val bytesWritten: Int) : ControllerWriteResult
    public object NotMounted : ControllerWriteResult
    public data class Failed(public val errorCode: Int, public val cause: Throwable? = null) : ControllerWriteResult
}

/** Diagnostic snapshot of the controller's view of the AudioTrack. */
public data class AudioTrackControllerSnapshot(
    public val mounted: Boolean,
    public val playing: Boolean,
    public val playState: Int,
    public val state: Int,
    public val framesWritten: Long,
    public val flushCount: Long,
    public val pauseCount: Long,
)

/**
 * Concentrates every AudioTrack-side mutation in one named class. Owned
 * by [SpeakerPlaybackEngine]; consumed by [RouteRecoveryEngine] and
 * [PlaybackGainController].
 */
public class AudioTrackController {

    private val trackRef: AtomicReference<AudioTrack?> = AtomicReference(null)
    private val playing: AtomicBoolean = AtomicBoolean(false)
    private val framesWritten: AtomicLong = AtomicLong(0L)
    private val flushCount: AtomicLong = AtomicLong(0L)
    private val pauseCount: AtomicLong = AtomicLong(0L)

    /** True if a track is currently mounted. */
    public fun isMounted(): Boolean = trackRef.get() != null

    /** True if the mounted track is currently in PLAYING state. */
    public fun isPlaying(): Boolean = playing.get()

    /**
     * Hand a fresh AudioTrack to the controller. The controller takes
     * ownership of the track's lifecycle; callers must NOT call
     * `track.release()` afterward — invoke [release] or [releaseAndUnmount]
     * instead.
     *
     * Returns [MountResult.Rejected] if the track is not in
     * `STATE_INITIALIZED`. Returns [MountResult.AlreadyMounted] (and does
     * NOT swap) if a track is already mounted — the caller must call
     * [releaseAndUnmount] first.
     */
    public fun mount(track: AudioTrack): MountResult {
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            DaemonLogger.get().warn(
                TAG,
                "controller.mount.rejected state=${track.state}",
            )
            return MountResult.Rejected("track_not_initialised")
        }
        val previous = trackRef.get()
        if (previous != null) {
            DaemonLogger.get().warn(
                TAG,
                "controller.mount.already_mounted framesWritten=${framesWritten.get()}",
            )
            return MountResult.AlreadyMounted(framesWritten.get())
        }
        trackRef.set(track)
        // Reset bookkeeping for the new mount.
        framesWritten.set(0L)
        flushCount.set(0L)
        pauseCount.set(0L)
        playing.set(false)
        DaemonLogger.get().info(TAG, "controller.mount.success")
        return MountResult.Mounted
    }

    /**
     * Equivalent of `track.play()`. Returns true if the call landed on a
     * mounted track and did not throw.
     */
    public fun play(): Boolean {
        val track = trackRef.get() ?: return false
        return try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            playing.set(true)
            true
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.play.threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            false
        }
    }

    /**
     * Equivalent of `track.pause()`. The PCM data queued in the AudioTrack
     * is preserved — call [flush] to discard it.
     */
    public fun pause(): Boolean {
        val track = trackRef.get() ?: return false
        return try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                pauseCount.incrementAndGet()
            }
            playing.set(false)
            true
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.pause.threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            false
        }
    }

    /**
     * Equivalent of `track.flush()`. Must be invoked when the track is
     * paused or stopped — calling flush on a playing track is a no-op on
     * most HALs but logs a warning at the framework layer.
     */
    public fun flush(): Boolean {
        val track = trackRef.get() ?: return false
        return try {
            track.flush()
            flushCount.incrementAndGet()
            DaemonLogger.get().debug(TAG, "controller.flush total=${flushCount.get()}")
            true
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.flush.threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            false
        }
    }

    /**
     * Equivalent of `track.stop()`. Drains the queued PCM, transitions the
     * track to STOPPED, and toggles the playing flag. Use [release] to
     * actually free the native buffer.
     */
    public fun stop(): Boolean {
        val track = trackRef.get() ?: return false
        return try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                track.stop()
            }
            playing.set(false)
            true
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.stop.threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            false
        }
    }

    /**
     * Set the playback gain. Both stereo channels share [gain]. Use
     * [PlaybackGainController] for normalised volume management; this is
     * the unwrapped underlying API exposed for that class to call into.
     */
    public fun setVolume(gain: Float): Boolean {
        val track = trackRef.get() ?: return false
        return try {
            val resultCode = track.setVolume(gain)
            if (resultCode != AudioTrack.SUCCESS) {
                DaemonLogger.get().warn(
                    TAG,
                    "controller.set_volume.failed gain=$gain code=$resultCode",
                )
                return false
            }
            true
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.set_volume.threw gain=$gain err=${t.javaClass.simpleName}",
            )
            false
        }
    }

    /**
     * Blocking write to the underlying AudioTrack. The engine's playback
     * loop calls this every frame; returns the bytes-written count or a
     * negative error code.
     */
    public fun write(buffer: ByteArray, offset: Int, length: Int): ControllerWriteResult {
        val track = trackRef.get() ?: return ControllerWriteResult.NotMounted
        return try {
            val written = track.write(buffer, offset, length)
            if (written < 0) {
                return ControllerWriteResult.Failed(written)
            }
            if (written > 0) {
                framesWritten.incrementAndGet()
            }
            ControllerWriteResult.Wrote(written)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.write.threw len=$length err=${t.javaClass.simpleName} msg=${t.message}",
            )
            ControllerWriteResult.Failed(WRITE_THREW, t)
        }
    }

    /** Release the mounted track without unmounting (used when re-entering). */
    public fun release() {
        val track = trackRef.get() ?: return
        try {
            track.release()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "controller.release.threw err=${t.javaClass.simpleName}",
            )
        }
        playing.set(false)
    }

    /**
     * Release the mounted track AND clear the slot so the controller can
     * accept a new mount. Returns the previous frames-written count for
     * forensics.
     */
    public fun releaseAndUnmount(): Long {
        val track = trackRef.getAndSet(null)
        val prev = framesWritten.get()
        if (track != null) {
            try {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    track.stop()
                }
            } catch (_: Throwable) {
                // ignore — release below.
            }
            try {
                track.release()
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "controller.release_and_unmount.release_threw err=${t.javaClass.simpleName}",
                )
            }
        }
        playing.set(false)
        DaemonLogger.get().info(
            TAG,
            "controller.release_and_unmount framesWritten=$prev flushes=${flushCount.get()} pauses=${pauseCount.get()}",
        )
        return prev
    }

    /** Diagnostic snapshot — safe to call from any thread. */
    public fun snapshot(): AudioTrackControllerSnapshot {
        val track = trackRef.get()
        val playState = track?.playState ?: AudioTrack.PLAYSTATE_STOPPED
        val state = track?.state ?: AudioTrack.STATE_UNINITIALIZED
        return AudioTrackControllerSnapshot(
            mounted = track != null,
            playing = playing.get(),
            playState = playState,
            state = state,
            framesWritten = framesWritten.get(),
            flushCount = flushCount.get(),
            pauseCount = pauseCount.get(),
        )
    }

    public companion object {
        /** Returned when AudioTrack.write threw rather than returned an error code. */
        public const val WRITE_THREW: Int = -9001
        private const val TAG: String = "AudioTrackController"
    }
}

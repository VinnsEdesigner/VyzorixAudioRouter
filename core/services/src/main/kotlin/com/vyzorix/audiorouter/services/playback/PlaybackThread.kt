// PlaybackThread — owns the high-priority worker thread the playback
// loop runs on.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 641:
//     core/services/playback/PlaybackThread.kt
//       "High-priority worker thread for output write loops".
//
// Why a dedicated thread (vs Dispatchers.IO):
//   - IO is a shared elastic pool with up to 64 worker threads; the
//     playback loop is sub-millisecond critical and we don't want it
//     contending with arbitrary I/O work spun up by the rest of the
//     daemon (log rotation, database writes, etc.).
//   - We pin `THREAD_PRIORITY_AUDIO` (= -16) the moment the HandlerThread
//     reaches `looperReady` so the scheduler treats us at the same niceness
//     band as audioserver. Per `doc/NOKIA_C22_NOTES.md §SCHED_FIFO` the
//     Unisoc kernel silently downgrades real-time priority requests; we
//     verify the resulting `Process.getThreadPriority()` and log a warning
//     if the OS rejected our hint so the daemon can switch to
//     `UnisocPlatformTweaks` mitigation instead of pretending we are RT.
//
// Single-instance per `SpeakerPlaybackEngine`. Reusable after `quit()`.

package com.vyzorix.audiorouter.services.playback

import android.os.HandlerThread
import android.os.Process
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

/** Outcome of [PlaybackThread.start]. */
public sealed interface PlaybackThreadStartResult {
    public data class Started(public val dispatcher: CoroutineDispatcher) : PlaybackThreadStartResult
    public data class AlreadyStarted(public val dispatcher: CoroutineDispatcher) : PlaybackThreadStartResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : PlaybackThreadStartResult
}

/** Diagnostic snapshot for the dashboard / forensics. */
public data class PlaybackThreadSnapshot(
    public val started: Boolean,
    public val threadId: Int,
    public val requestedPriority: Int,
    public val observedPriority: Int,
    public val schedulerFallback: Boolean,
)

/**
 * Owns a single named `HandlerThread` with `THREAD_PRIORITY_AUDIO` and an
 * associated coroutine dispatcher. Designed for one playback engine; the
 * SpeakerPlaybackEngine instance hands this dispatcher to its loop.
 */
public class PlaybackThread(
    private val threadName: String = DEFAULT_THREAD_NAME,
    private val requestedPriority: Int = Process.THREAD_PRIORITY_AUDIO,
    private val priorityReader: (Int) -> Int = { Process.getThreadPriority(it) },
    private val prioritySetter: (Int, Int) -> Unit = { tid, prio -> Process.setThreadPriority(tid, prio) },
) {

    private val started: AtomicBoolean = AtomicBoolean(false)
    private val handlerThreadRef: AtomicReference<HandlerThread?> = AtomicReference(null)
    private val dispatcherRef: AtomicReference<CoroutineDispatcher?> = AtomicReference(null)
    private val threadId: AtomicInteger = AtomicInteger(-1)
    private val observedPriority: AtomicInteger = AtomicInteger(Int.MIN_VALUE)
    private val schedulerFallback: AtomicBoolean = AtomicBoolean(false)

    /** True when the thread is alive and the dispatcher is wired. */
    public fun isStarted(): Boolean = started.get()

    /** The dispatcher backed by this thread, or null if not started. */
    public fun dispatcher(): CoroutineDispatcher? = dispatcherRef.get()

    /**
     * Spin up the thread + dispatcher. Idempotent — subsequent calls
     * return the existing dispatcher.
     */
    public fun start(): PlaybackThreadStartResult {
        val existing = dispatcherRef.get()
        if (started.get() && existing != null) {
            return PlaybackThreadStartResult.AlreadyStarted(existing)
        }
        val thread = HandlerThread(threadName)
        return try {
            thread.start()
            // HandlerThread.looper blocks until the looper is ready.
            val looper = thread.looper
            val tid = thread.threadId
            threadId.set(tid)
            try {
                prioritySetter(tid, requestedPriority)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "playback.thread.priority_set_failed tid=$tid requested=$requestedPriority err=${t.javaClass.simpleName} msg=${t.message}",
                )
            }
            val observed = try {
                priorityReader(tid)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "playback.thread.priority_read_failed tid=$tid err=${t.javaClass.simpleName}",
                )
                Int.MIN_VALUE
            }
            observedPriority.set(observed)
            val fellBack = observed != Int.MIN_VALUE && observed > requestedPriority
            schedulerFallback.set(fellBack)
            if (fellBack) {
                DaemonLogger.get().warn(
                    TAG,
                    "playback.thread.priority_fallback requested=$requestedPriority observed=$observed " +
                        "(scheduler silently downgraded; UnisocPlatformTweaks should compensate cadence)",
                )
            } else {
                DaemonLogger.get().info(
                    TAG,
                    "playback.thread.started tid=$tid name=$threadName priority=$observed",
                )
            }
            val dispatcher: CoroutineDispatcher = android.os.Handler(looper).asCoroutineDispatcher(threadName)
            handlerThreadRef.set(thread)
            dispatcherRef.set(dispatcher)
            started.set(true)
            PlaybackThreadStartResult.Started(dispatcher)
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "playback.thread.start_failed err=${t.javaClass.simpleName} msg=${t.message}",
            )
            try {
                thread.quitSafely()
            } catch (_: Throwable) {
                // Best-effort cleanup.
            }
            PlaybackThreadStartResult.Failed("handler_thread_start_threw", t)
        }
    }

    /** Stop the thread and tear down the dispatcher. Safe to call from any thread. */
    public fun quit() {
        if (!started.compareAndSet(true, false)) return
        val thread = handlerThreadRef.getAndSet(null)
        dispatcherRef.set(null)
        if (thread != null) {
            try {
                thread.quitSafely()
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "playback.thread.quit_failed err=${t.javaClass.simpleName}",
                )
            }
        }
        DaemonLogger.get().info(TAG, "playback.thread.quit threadId=${threadId.get()}")
    }

    /** Diagnostic snapshot for the dashboard. */
    public fun snapshot(): PlaybackThreadSnapshot =
        PlaybackThreadSnapshot(
            started = started.get(),
            threadId = threadId.get(),
            requestedPriority = requestedPriority,
            observedPriority = observedPriority.get(),
            schedulerFallback = schedulerFallback.get(),
        )

    public companion object {
        public const val DEFAULT_THREAD_NAME: String = "vyzorix-playback"
        private const val TAG: String = "PlaybackThread"
    }
}

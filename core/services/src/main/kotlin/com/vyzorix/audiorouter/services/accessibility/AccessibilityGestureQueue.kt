// AccessibilityGestureQueue — bounded queue of accessibility-driven
// gesture intents (click, scroll, etc).
//
// Why a queue rather than firing directly:
//   1. Rate-limiting. AccessibilityNodeInfo.performAction() can be
//      invoked at most ~5 Hz before AOSP rate-limits us. The queue
//      enforces a min interval between dispatches.
//   2. Single-thread audit. Every click that flows through the daemon's
//      accessibility surface goes through ONE class so failures
//      are easy to attribute.
//   3. Per-gesture metadata (intent, originating snapshot) gives us
//      forensic value when reviewing logs.
//
// The queue is bounded — when full, oldest gestures are dropped (with
// a logged metric). The default capacity covers normal load.
//
// Per doc/BUILD_ORDER.md §Layer 4 + DOC_3 §5/§6.

package com.vyzorix.audiorouter.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A queued gesture. */
public data class QueuedGesture(
    public val node: AccessibilityNodeInfo,
    public val action: Int,
    public val origin: String,
    public val createdAtUptimeMs: Long,
) {
    public companion object {
        public const val ACTION_CLICK: Int = AccessibilityNodeInfo.ACTION_CLICK
        public const val ACTION_LONG_CLICK: Int = AccessibilityNodeInfo.ACTION_LONG_CLICK
    }
}

/** Outcome of [AccessibilityGestureQueue.dispatchOnce]. */
public sealed interface GestureDispatchResult {
    public data class Dispatched(public val origin: String) : GestureDispatchResult
    public object QueueEmpty : GestureDispatchResult
    public data class Failed(public val origin: String, public val cause: Throwable? = null) : GestureDispatchResult
    public data class RateLimited(public val origin: String, public val sleepMs: Long) : GestureDispatchResult
}

/**
 * Bounded gesture queue with built-in rate limiting. Single-instance per
 * accessibility-service / daemon process.
 */
public class AccessibilityGestureQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val minDispatchIntervalMs: Long = DEFAULT_MIN_DISPATCH_INTERVAL_MS,
    private val uptimeMs: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {

    private val queue: ArrayBlockingQueue<QueuedGesture> = ArrayBlockingQueue(capacity)
    private val dispatched: AtomicLong = AtomicLong(0L)
    private val dropped: AtomicLong = AtomicLong(0L)
    private val lastDispatchedUptimeMs: AtomicLong = AtomicLong(0L)
    private val draining: AtomicBoolean = AtomicBoolean(false)

    /** Total gestures successfully dispatched. */
    public val totalDispatched: Long get() = dispatched.get()

    /** Total gestures dropped due to a full queue. */
    public val totalDropped: Long get() = dropped.get()

    /** Number of gestures currently waiting in the queue. */
    public val pendingSize: Int get() = queue.size

    /**
     * Enqueue a gesture. If the queue is full, the OLDEST gesture is
     * evicted and dropped — return value indicates whether the new gesture
     * was admitted.
     */
    public fun enqueue(
        node: AccessibilityNodeInfo,
        action: Int = QueuedGesture.ACTION_CLICK,
        origin: String,
    ): Boolean {
        val gesture = QueuedGesture(
            node = node,
            action = action,
            origin = origin,
            createdAtUptimeMs = uptimeMs(),
        )
        val admitted = queue.offer(gesture)
        if (!admitted) {
            // Drop the oldest, push the newest.
            queue.poll()
            dropped.incrementAndGet()
            val added = queue.offer(gesture)
            DaemonLogger.get().warn(
                TAG,
                "queue.evicted_oldest origin=$origin newOrigin=$origin admittedRetry=$added totalDropped=${dropped.get()}",
            )
        }
        return admitted
    }

    /**
     * Attempt to dispatch the next gesture. Respects the min-interval
     * rate limit. Returns the outcome.
     *
     * This is intentionally synchronous — callers can schedule it on
     * a Handler/coroutine of their choice. We intentionally don't own
     * the dispatch coroutine to keep the surface testable.
     */
    public fun dispatchOnce(): GestureDispatchResult {
        if (!draining.compareAndSet(false, true)) {
            return GestureDispatchResult.QueueEmpty
        }
        try {
            val gesture = queue.poll() ?: return GestureDispatchResult.QueueEmpty
            val now = uptimeMs()
            val sinceLast = now - lastDispatchedUptimeMs.get()
            if (lastDispatchedUptimeMs.get() != 0L && sinceLast < minDispatchIntervalMs) {
                // Push back to the head conceptually — ArrayBlockingQueue
                // doesn't offer head-reinsert; use addFirst-like via a tmp
                // list. Cheaper: rebuild on the rare rate-limit hit.
                val remaining = ArrayList<QueuedGesture>(queue.size + 1)
                remaining.add(gesture)
                while (true) {
                    val next = queue.poll() ?: break
                    remaining.add(next)
                }
                remaining.forEach { queue.offer(it) }
                return GestureDispatchResult.RateLimited(
                    origin = gesture.origin,
                    sleepMs = minDispatchIntervalMs - sinceLast,
                )
            }
            val ok = try {
                gesture.node.performAction(gesture.action)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "gesture.dispatch.threw origin=${gesture.origin} err=${t.javaClass.simpleName} msg=${t.message}",
                )
                return GestureDispatchResult.Failed(origin = gesture.origin, cause = t)
            }
            return if (ok) {
                dispatched.incrementAndGet()
                lastDispatchedUptimeMs.set(now)
                DaemonLogger.get().info(
                    TAG,
                    "gesture.dispatched origin=${gesture.origin} action=${gesture.action} total=${dispatched.get()}",
                )
                GestureDispatchResult.Dispatched(origin = gesture.origin)
            } else {
                DaemonLogger.get().warn(
                    TAG,
                    "gesture.dispatch.refused origin=${gesture.origin}",
                )
                GestureDispatchResult.Failed(origin = gesture.origin)
            }
        } finally {
            draining.set(false)
        }
    }

    /** Drop all queued gestures. */
    public fun clear() {
        queue.clear()
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 16
        public const val DEFAULT_MIN_DISPATCH_INTERVAL_MS: Long = 200L
        private const val TAG: String = "AccessibilityGestureQueue"
    }
}

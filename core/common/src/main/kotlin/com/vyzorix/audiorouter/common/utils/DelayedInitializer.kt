// DelayedInitializer — defers heavy startup tasks (e.g. accessibility event
// subscription, MediaProjection request) so they don't compete for CPU with
// Zygote-stage initialisation on the Nokia C22.
//
// Used by Layer 4+ bootstrap code; the surface lives here so Layer 0 stays
// dependency-light and consumers can wire it without touching the host
// service class hierarchy.

package com.vyzorix.audiorouter.common.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Schedules an [initBlock] to run after [delayMillis] on [scope]. The
 * returned [Job] can be cancelled before fire-time without leaking the
 * delay.
 *
 * Multiple [schedule] calls on the same instance are independent — they run
 * concurrently if [scope] supports it. Use a single-coroutine
 * [kotlinx.coroutines.SupervisorJob] dispatcher when serialisation is required.
 */
public class DelayedInitializer(private val scope: CoroutineScope) {

    public fun schedule(
        delayMillis: Long,
        initBlock: suspend () -> Unit,
    ): Job {
        require(delayMillis >= 0) { "delayMillis must be >= 0: $delayMillis" }
        return scope.launch {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            initBlock()
        }
    }
}

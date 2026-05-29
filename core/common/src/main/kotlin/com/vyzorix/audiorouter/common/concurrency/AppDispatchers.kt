package com.vyzorix.audiorouter.common.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable bundle of [CoroutineDispatcher] used by every layer above Layer 0.
 *
 * The Main dispatcher reference is kept here even though Layer 0 is pure Kotlin
 * — it's only resolved when a coroutine is actually launched, and the daemon
 * always supplies an Android-backed Main from the `app` module at runtime. In
 * unit tests we substitute [Dispatchers.Unconfined] or kotlinx-coroutines-test
 * dispatchers.
 */
public interface AppDispatchers {
    public val io: CoroutineDispatcher
    public val default: CoroutineDispatcher
    public val main: CoroutineDispatcher
    public val unconfined: CoroutineDispatcher
}

/** Real-runtime [AppDispatchers] backed by [Dispatchers]. */
public object DefaultAppDispatchers : AppDispatchers {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}

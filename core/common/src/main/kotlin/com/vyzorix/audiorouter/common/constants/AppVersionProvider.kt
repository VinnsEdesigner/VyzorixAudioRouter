package com.vyzorix.audiorouter.common.constants

/**
 * Abstraction exposing VERSION_NAME and VERSION_CODE to core/services modules
 * that cannot directly reference app-level BuildConfig.
 *
 * Layer 0 defines the holder; the `app` module populates it at startup via
 * [initialize] from BuildInfo.kt.
 */
public object AppVersionProvider {
    @Volatile public var versionName: String = "0.0.0-unset"
        private set
    @Volatile public var versionCode: Int = 0
        private set

    /**
     * Called once from Application.onCreate() in the `app` module.
     * Thread-safe via @Volatile; no lock needed — single writer at startup.
     */
    public fun initialize(name: String, code: Int) {
        versionName = name
        versionCode = code
    }
}

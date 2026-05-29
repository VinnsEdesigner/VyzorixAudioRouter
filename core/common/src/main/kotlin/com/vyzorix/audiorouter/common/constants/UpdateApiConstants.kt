package com.vyzorix.audiorouter.common.constants

/**
 * Server base URLs, API paths, and timing intervals for the update / C2 subsystem.
 * Values here must stay in sync with doc/UPDATE_MECHANISM.md and the mock-server
 * endpoints in vyzorix-update-server/cmd/mockserver/.
 */
public object UpdateApiConstants {
    public const val BASE_URL_PRODUCTION: String = "https://vyzorix-update-server.onrender.com"
    public const val BASE_URL_MOCK: String = "http://10.0.2.2:8080"

    public const val PATH_VERSION: String = "/api/v1/version"
    public const val PATH_APK: String = "/api/v1/apk"
    public const val PATH_REGISTER: String = "/v1/device/register"
    public const val PATH_HEALTH: String = "/healthz"

    /** WSS path template — {id} replaced at runtime. */
    public const val PATH_STREAM_TEMPLATE: String = "/v1/device/{id}/stream"

    public const val UPDATE_CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L // 6 h
    public const val WS_RECONNECT_BASE_MS: Long = 5_000L
    public const val WS_RECONNECT_MAX_MS: Long = 5 * 60 * 1000L // 5 min cap
    public const val WS_PING_INTERVAL_MS: Long = 30_000L
}

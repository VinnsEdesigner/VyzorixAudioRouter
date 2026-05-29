package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/**
 * High-level lifecycle state of the daemon, as surfaced to dashboards and
 * remote operators. Ordered from "not yet booted" to "stopped".
 *
 * See SYSTEM_MAP.md §3 (Lifecycle) for the transition diagram.
 */
@Serializable
public enum class DaemonState {
    /** APK installed, foreground service has not been started yet. */
    INSTALLED,

    /** Application object created; pre-service initializers running. */
    BOOTSTRAP,

    /** Service started; waiting on MediaProjection / permissions. */
    INITIALIZING,

    /** All dependencies acquired; about to flip to RUNNING. */
    PENDING,

    /** Steady-state: route enforced, capture active, dashboard updating. */
    RUNNING,

    /** Reduced-functionality mode after repeated failures (no projection / no capture). */
    SAFE_MODE,

    /** Recovering from a transient failure (e.g. projection death). */
    RECOVERING,

    /** Last loop crashed; service is being torn down. */
    CRASHED,

    /** Service stopped intentionally (user / remote command / system). */
    STOPPED,
}

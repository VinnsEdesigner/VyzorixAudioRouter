package com.vyzorix.audiorouter.common.constants

/** Custom broadcast action strings used for inter-component communication. */
public object BroadcastActions {
    private const val PREFIX = "com.vyzorix.audiorouter.action."

    public const val DAEMON_STATUS_CHANGED: String = "${PREFIX}DAEMON_STATUS_CHANGED"
    public const val ROUTE_STATE_CHANGED: String = "${PREFIX}ROUTE_STATE_CHANGED"
    public const val CAPTURE_STATE_CHANGED: String = "${PREFIX}CAPTURE_STATE_CHANGED"
    public const val UPDATE_AVAILABLE: String = "${PREFIX}UPDATE_AVAILABLE"
    public const val COMMAND_RECEIVED: String = "${PREFIX}COMMAND_RECEIVED"
    public const val SAFE_MODE_ENTERED: String = "${PREFIX}SAFE_MODE_ENTERED"
    public const val CRASH_DETECTED: String = "${PREFIX}CRASH_DETECTED"
}

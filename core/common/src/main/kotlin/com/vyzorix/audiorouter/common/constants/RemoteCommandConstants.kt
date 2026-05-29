package com.vyzorix.audiorouter.common.constants

/**
 * Well-known remote command names, HMAC header names, and telemetry keys.
 * Values are the wire-format strings exchanged over WSS/FCM between the
 * mock-server (and later the real server) and the device.
 *
 * See doc/COMMAND_SECURITY.md §2 for the CommandFrame schema.
 */
public object RemoteCommandConstants {
    // --- Command actions (server → device) ---
    public const val CMD_REINIT_PROJECTION: String = "REINIT_PROJECTION"
    public const val CMD_FORCE_SPEAKER: String = "FORCE_SPEAKER"
    public const val CMD_ENTER_SAFE_MODE: String = "ENTER_SAFE_MODE"
    public const val CMD_EXIT_SAFE_MODE: String = "EXIT_SAFE_MODE"
    public const val CMD_RESTART_DAEMON: String = "RESTART_DAEMON"
    public const val CMD_COLLECT_DIAGNOSTICS: String = "COLLECT_DIAGNOSTICS"
    public const val CMD_TRIGGER_UPDATE_CHECK: String = "TRIGGER_UPDATE_CHECK"
    public const val CMD_PING: String = "PING"

    // --- HMAC headers (REST API layer — see hmac.go) ---
    public const val HEADER_SIGNATURE: String = "X-Vyzorix-Signature"
    public const val HEADER_NONCE: String = "X-Vyzorix-Nonce"
    public const val HEADER_TIMESTAMP: String = "X-Vyzorix-Timestamp"

    // --- Telemetry frame types (device → server over WSS) ---
    public const val TELEMETRY_STATUS: String = "status"
    public const val TELEMETRY_ACK: String = "ack"
    public const val TELEMETRY_HEARTBEAT: String = "heartbeat"

    // --- WSS frame types (server → device) ---
    public const val FRAME_TYPE_COMMAND: String = "command"
}

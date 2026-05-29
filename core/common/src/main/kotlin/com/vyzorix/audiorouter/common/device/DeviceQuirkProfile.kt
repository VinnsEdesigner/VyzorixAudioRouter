package com.vyzorix.audiorouter.common.device

/**
 * Runtime profile capturing all device-specific knobs the daemon respects.
 *
 * See ADR-0008 and doc/DEVICE_QUIRK_PROFILES.md for the rationale and the
 * field-by-field meaning. Adding support for a new device is "add a new
 * [DeviceQuirkProfile] constant and wire it into [DeviceQuirkRegistry]" — no
 * code changes elsewhere.
 */
public data class DeviceQuirkProfile(
    val deviceClass: String,
    val socFamily: SocFamily,
    val schedulerBehavior: SchedulerBehavior,
    val keystoreReliability: KeystoreReliability,
    val backgroundRestrictionLevel: BackgroundRestrictionLevel,
    val thermalZones: List<String>,
    val alsaTimingGapMs: Int,
    val audioModeQuirks: Set<AudioModeQuirk>,
    val notes: String = "",
)

public enum class SocFamily {
    UNISOC_SC9863A,
    MEDIATEK,
    QUALCOMM,
    SAMSUNG_EXYNOS,
    UNKNOWN,
}

/** How the kernel handles SCHED_FIFO requests from the audio engine. */
public enum class SchedulerBehavior {
    /** Honors SCHED_FIFO without intervention. */
    RELIABLE_SCHED_FIFO,

    /** Silently downgrades SCHED_FIFO to SCHED_OTHER; engine must compensate. */
    SILENT_FALLBACK,

    /** Honors the request but throttles the thread under thermal pressure. */
    KNOWN_DEGRADED,
}

/** Whether Android Keystore (TEE/StrongBox) can be trusted on this device. */
public enum class KeystoreReliability {
    RELIABLE,
    UNRELIABLE_USE_SOFTWARE_FALLBACK,
}

/** How aggressively the OEM kills backgrounded foreground services. */
public enum class BackgroundRestrictionLevel {
    /** Stock AOSP-like behavior. */
    PERMISSIVE,

    /** Some OEM doze/kill heuristics, but foreground services are protected. */
    MODERATE,

    /** Aggressive OEM killer; daemon must use additional anchors. */
    AGGRESSIVE,
}

/** Per-device tweaks the audio engine applies when entering MODE_IN_COMMUNICATION. */
public enum class AudioModeQuirk {
    /** The HAL needs a brief silence-gap when switching modes; SpeakerForceEngine
     * inserts a 2 ms pause before re-asserting setSpeakerphoneOn(true). */
    NEEDS_MODE_SWITCH_GAP,

    /** AudioFocusRequest with USAGE_VOICE_COMMUNICATION reports a phantom headset
     * route at boot. Daemon must explicitly stop+start to reset. */
    PHANTOM_HEADSET_AT_BOOT,

    /** Bluetooth SCO toggle is unreliable; never call startBluetoothSco(). */
    UNRELIABLE_BLUETOOTH_SCO,
}

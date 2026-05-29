package com.vyzorix.audiorouter.common.device.profiles

import com.vyzorix.audiorouter.common.constants.AppConstants
import com.vyzorix.audiorouter.common.device.AudioModeQuirk
import com.vyzorix.audiorouter.common.device.BackgroundRestrictionLevel
import com.vyzorix.audiorouter.common.device.DeviceQuirkProfile
import com.vyzorix.audiorouter.common.device.KeystoreReliability
import com.vyzorix.audiorouter.common.device.SchedulerBehavior
import com.vyzorix.audiorouter.common.device.SocFamily

/**
 * Profile for the Nokia C22 (TA-1502 family) — Unisoc SC9863A, Android 13.
 * See doc/NOKIA_C22_NOTES.md for the per-field rationale.
 */
public val NokiaC22Profile: DeviceQuirkProfile = DeviceQuirkProfile(
    deviceClass = AppConstants.DEVICE_CLASS_NOKIA_C22,
    socFamily = SocFamily.UNISOC_SC9863A,
    schedulerBehavior = SchedulerBehavior.SILENT_FALLBACK,
    keystoreReliability = KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK,
    backgroundRestrictionLevel = BackgroundRestrictionLevel.MODERATE,
    thermalZones = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
    ),
    alsaTimingGapMs = 2,
    audioModeQuirks = setOf(
        AudioModeQuirk.NEEDS_MODE_SWITCH_GAP,
        AudioModeQuirk.PHANTOM_HEADSET_AT_BOOT,
        AudioModeQuirk.UNRELIABLE_BLUETOOTH_SCO,
    ),
    notes = "Fried hardware codec — speaker output is software-only via MediaProjection capture.",
)

package com.vyzorix.audiorouter.common.device.profiles

import com.vyzorix.audiorouter.common.constants.AppConstants
import com.vyzorix.audiorouter.common.device.BackgroundRestrictionLevel
import com.vyzorix.audiorouter.common.device.DeviceQuirkProfile
import com.vyzorix.audiorouter.common.device.KeystoreReliability
import com.vyzorix.audiorouter.common.device.SchedulerBehavior
import com.vyzorix.audiorouter.common.device.SocFamily

/**
 * Safe-defaults profile for any device that hasn't been individually characterized.
 * The daemon runs in a degraded-but-functional mode against this profile —
 * software keystore fallback, no SCHED_FIFO assumptions, aggressive recovery.
 */
public val UnknownDeviceProfile: DeviceQuirkProfile = DeviceQuirkProfile(
    deviceClass = AppConstants.DEVICE_CLASS_UNKNOWN,
    socFamily = SocFamily.UNKNOWN,
    schedulerBehavior = SchedulerBehavior.SILENT_FALLBACK,
    keystoreReliability = KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK,
    backgroundRestrictionLevel = BackgroundRestrictionLevel.AGGRESSIVE,
    thermalZones = emptyList(),
    alsaTimingGapMs = 0,
    audioModeQuirks = emptySet(),
    notes = "Unrecognized device — running on safe defaults.",
)

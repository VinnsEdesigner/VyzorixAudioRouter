package com.vyzorix.audiorouter.common.device

import com.vyzorix.audiorouter.common.device.profiles.NokiaC22Profile
import com.vyzorix.audiorouter.common.device.profiles.UnknownDeviceProfile

/**
 * Resolves the active [DeviceQuirkProfile] for a given (manufacturer, model) pair.
 *
 * Layer 0 stays Android-free, so the resolver takes plain strings. The `app`
 * module supplies Build.MANUFACTURER / Build.MODEL at runtime; tests pass any
 * pair.
 */
public object DeviceQuirkRegistry {

    public fun forDevice(manufacturer: String, model: String): DeviceQuirkProfile {
        val mfg = manufacturer.trim().lowercase()
        val mdl = model.trim().uppercase()

        return when {
            mfg == "hmd global" && mdl.startsWith("TA-1502") -> NokiaC22Profile
            else -> UnknownDeviceProfile
        }
    }
}

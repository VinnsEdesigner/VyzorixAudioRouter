package com.vyzorix.audiorouter.common.audio

import android.media.AudioDeviceInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDeviceUtilsTest {

    @Test
    fun type_name_covers_documented_types() {
        assertEquals("BUILTIN_SPEAKER", AudioDeviceUtils.typeName(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("WIRED_HEADSET", AudioDeviceUtils.typeName(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("BLUETOOTH_A2DP", AudioDeviceUtils.typeName(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("USB_HEADSET", AudioDeviceUtils.typeName(AudioDeviceInfo.TYPE_USB_HEADSET))
    }

    @Test
    fun type_name_unknown_includes_numeric_code() {
        val result = AudioDeviceUtils.typeName(-1)
        assertTrue(result.startsWith("UNKNOWN("))
        assertTrue(result.endsWith(")"))
    }

    @Test
    fun is_built_in_output_partitions_correctly() {
        assertTrue(AudioDeviceUtils.isBuiltInOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(AudioDeviceUtils.isBuiltInOutput(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(AudioDeviceUtils.isBuiltInOutput(AudioDeviceInfo.TYPE_WIRED_HEADSET))
    }

    @Test
    fun headset_predicates_partition_correctly() {
        assertTrue(AudioDeviceUtils.isWiredHeadset(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertTrue(AudioDeviceUtils.isWiredHeadset(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertTrue(AudioDeviceUtils.isWiredHeadset(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(AudioDeviceUtils.isBluetooth(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioDeviceUtils.isBluetooth(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioDeviceUtils.isHeadsetLike(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(AudioDeviceUtils.isHeadsetLike(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}

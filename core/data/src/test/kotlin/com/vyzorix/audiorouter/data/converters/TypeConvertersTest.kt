package com.vyzorix.audiorouter.data.converters

import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.common.enums.UpdateState
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.PermissionOutcome
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Asserts the enum-name round-trip contract for every TypeConverter.
 * The converters MUST persist the enum's `name` string, not its ordinal —
 * ordinal would silently break when enum cases get reordered.
 */
class TypeConvertersTest {

    @Test
    fun date_time_round_trip() {
        val converter = DateTimeTypeConverters()
        val original = Instant.ofEpochMilli(1_700_000_000_000L)
        val epoch = converter.fromInstant(original)
        assertEquals(1_700_000_000_000L, epoch)
        assertEquals(original, converter.toInstant(epoch))
        assertNull(converter.fromInstant(null))
        assertNull(converter.toInstant(null))
    }

    @Test
    fun daemon_state_round_trip_all_values() {
        val converter = DaemonStateTypeConverters()
        for (value in DaemonState.values()) {
            assertEquals(value.name, converter.fromDaemonState(value))
            assertEquals(value, converter.toDaemonState(value.name))
        }
        for (value in RouteState.values()) {
            assertEquals(value.name, converter.fromRouteState(value))
            assertEquals(value, converter.toRouteState(value.name))
        }
        for (value in CaptureState.values()) {
            assertEquals(value.name, converter.fromCaptureState(value))
            assertEquals(value, converter.toCaptureState(value.name))
        }
    }

    @Test
    fun crash_event_round_trip_all_values() {
        val converter = CrashEventTypeConverters()
        for (value in CrashType.values()) {
            assertEquals(value.name, converter.fromCrashType(value))
            assertEquals(value, converter.toCrashType(value.name))
        }
    }

    @Test
    fun update_state_round_trip_all_values() {
        val converter = UpdateStateTypeConverters()
        for (value in UpdateState.values()) {
            assertEquals(value.name, converter.fromUpdateState(value))
            assertEquals(value, converter.toUpdateState(value.name))
        }
    }

    @Test
    fun audio_route_round_trip_all_values() {
        val converter = AudioRouteTypeConverters()
        for (value in AudioRouteKind.values()) {
            assertEquals(value.name, converter.fromAudioRouteKind(value))
            assertEquals(value, converter.toAudioRouteKind(value.name))
        }
        for (value in RouteTransitionReason.values()) {
            assertEquals(value.name, converter.fromRouteTransitionReason(value))
            assertEquals(value, converter.toRouteTransitionReason(value.name))
        }
    }

    @Test
    fun permission_grant_round_trip_all_values() {
        val converter = PermissionGrantTypeConverters()
        for (value in PermissionOutcome.values()) {
            assertEquals(value.name, converter.fromPermissionOutcome(value))
            assertEquals(value, converter.toPermissionOutcome(value.name))
        }
    }
}

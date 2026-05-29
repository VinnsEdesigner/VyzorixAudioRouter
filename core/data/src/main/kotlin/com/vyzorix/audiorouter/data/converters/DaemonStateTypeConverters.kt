package com.vyzorix.audiorouter.data.converters

import androidx.room.TypeConverter
import com.vyzorix.audiorouter.common.enums.CaptureState
import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState

/**
 * Bidirectional converters for the `DaemonStateSnapshot` table's enum columns.
 *
 * Enum-name strings (not ordinals) are used on disk so the `.db` file
 * remains human-readable in `sqlcipher-shell` dumps and survives enum
 * reordering across versions.
 *
 * `IllegalArgumentException` from `enumValueOf` is intentionally NOT caught
 * — an unknown enum-name string in the database is a corruption signal,
 * and Layer 6 recovery code is the right place to handle it (not silently
 * swallow it here).
 */
public class DaemonStateTypeConverters {

    // ---- DaemonState ----

    @TypeConverter
    public fun fromDaemonState(value: DaemonState): String = value.name

    @TypeConverter
    public fun toDaemonState(value: String): DaemonState = enumValueOf(value)

    // ---- RouteState ----

    @TypeConverter
    public fun fromRouteState(value: RouteState): String = value.name

    @TypeConverter
    public fun toRouteState(value: String): RouteState = enumValueOf(value)

    // ---- CaptureState ----

    @TypeConverter
    public fun fromCaptureState(value: CaptureState): String = value.name

    @TypeConverter
    public fun toCaptureState(value: String): CaptureState = enumValueOf(value)
}

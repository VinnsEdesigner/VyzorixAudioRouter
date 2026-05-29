package com.vyzorix.audiorouter.data.converters

import androidx.room.TypeConverter
import com.vyzorix.audiorouter.common.enums.CrashType

/**
 * Bidirectional converter for the `crash_events.crashType` column.
 *
 * Persists the [CrashType] enum's `name` string (not its ordinal) so the
 * table remains diff-able across schema evolutions where new enum entries
 * are appended.
 */
public class CrashEventTypeConverters {

    @TypeConverter
    public fun fromCrashType(value: CrashType): String = value.name

    @TypeConverter
    public fun toCrashType(value: String): CrashType = enumValueOf(value)
}

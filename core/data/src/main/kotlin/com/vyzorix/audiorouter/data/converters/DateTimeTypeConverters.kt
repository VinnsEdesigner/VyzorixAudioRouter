package com.vyzorix.audiorouter.data.converters

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Converts `java.time.Instant` to/from Unix-millis `Long` for SQLite storage.
 *
 * All on-disk timestamp columns use `Long` directly (no `Instant?` columns
 * exist today), so this converter is primarily a future-proofing utility:
 * Layer 5+ code that needs nullable `Instant` columns picks it up via
 * [com.vyzorix.audiorouter.data.database.AppDatabase]'s `@TypeConverters`
 * annotation.
 */
public class DateTimeTypeConverters {

    @TypeConverter
    public fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    public fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}

package com.vyzorix.audiorouter.data.converters

import androidx.room.TypeConverter
import com.vyzorix.audiorouter.data.entity.PermissionOutcome

/**
 * Bidirectional converter for the `permission_grants.outcome` column.
 *
 * Lives in this file rather than `PermissionGrantRecord.kt` so the
 * `@Entity` data class file remains pure schema declaration.
 */
public class PermissionGrantTypeConverters {

    @TypeConverter
    public fun fromPermissionOutcome(value: PermissionOutcome): String = value.name

    @TypeConverter
    public fun toPermissionOutcome(value: String): PermissionOutcome = enumValueOf(value)
}

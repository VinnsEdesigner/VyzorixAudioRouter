package com.vyzorix.audiorouter.data.converters

import androidx.room.TypeConverter
import com.vyzorix.audiorouter.common.enums.UpdateState

/** Bidirectional converter for the `update_state.updateState` column. */
public class UpdateStateTypeConverters {

    @TypeConverter
    public fun fromUpdateState(value: UpdateState): String = value.name

    @TypeConverter
    public fun toUpdateState(value: String): UpdateState = enumValueOf(value)
}

package com.vyzorix.audiorouter.data.converters

import androidx.room.TypeConverter
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason

/**
 * Bidirectional converters for the `route_history` table's enum columns.
 *
 * The route enums are owned by `core/data/entity/` (not `core/common/enums/`)
 * because they are storage-shape concerns specific to the audit log produced
 * by Layer 3's `RouteHistoryRecorder`. Adding them to `core/common` would
 * leak persistence semantics into the shared Layer 0 surface.
 */
public class AudioRouteTypeConverters {

    // ---- AudioRouteKind ----

    @TypeConverter
    public fun fromAudioRouteKind(value: AudioRouteKind): String = value.name

    @TypeConverter
    public fun toAudioRouteKind(value: String): AudioRouteKind = enumValueOf(value)

    // ---- RouteTransitionReason ----

    @TypeConverter
    public fun fromRouteTransitionReason(value: RouteTransitionReason): String = value.name

    @TypeConverter
    public fun toRouteTransitionReason(value: String): RouteTransitionReason = enumValueOf(value)
}

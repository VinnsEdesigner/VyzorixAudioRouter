package com.vyzorix.audiorouter.data.extensions

import android.database.Cursor
import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.common.extensions.forEachRow
import com.vyzorix.audiorouter.common.extensions.getIntOrNull
import com.vyzorix.audiorouter.common.extensions.getLongOrNull
import com.vyzorix.audiorouter.common.extensions.getStringOrNull
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.CrashEvent
import com.vyzorix.audiorouter.data.entity.RouteHistoryEntry
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason

/**
 * Typed `Cursor` decoders for `core/data` entities.
 *
 * Lives in `core/data/extensions/` (not `core/common/extensions/`)
 * because the entity types are owned by `core/data` and the layered
 * dependency rule forbids `core/common` from referencing them. The
 * generic primitives (`Cursor.toMaps()`, `Cursor.forEachRow { ... }`,
 * `Cursor.getLongOrNull(...)`) live in
 * `core/common/extensions/CursorExtensions.kt` and we delegate to them
 * here.
 *
 * These helpers exist for the rare path where Layer 6+ code receives a
 * raw `Cursor` from a low-level diagnostic dump and needs to decode it
 * without going through Room (e.g. when reading from a corrupted DB
 * recovered with `sqlcipher-shell`). The standard happy path goes
 * through Room DAOs and never touches these.
 */

/** Decodes every row of the cursor as a [CrashEvent]. Closes the cursor. */
public fun Cursor.toCrashEventList(): List<CrashEvent> {
    val out = ArrayList<CrashEvent>(count.coerceAtLeast(0))
    forEachRow { cursor ->
        val crashTypeName = cursor.getStringOrNull("crashType") ?: return@forEachRow
        val crashType = runCatching { CrashType.valueOf(crashTypeName) }.getOrNull()
            ?: return@forEachRow
        out.add(
            CrashEvent(
                id = cursor.getLongOrNull("id") ?: 0L,
                epochMs = cursor.getLongOrNull("epochMs") ?: 0L,
                crashType = crashType,
                signature = cursor.getStringOrNull("signature").orEmpty(),
                stackHead = cursor.getStringOrNull("stackHead").orEmpty(),
                processUptimeMs = cursor.getLongOrNull("processUptimeMs") ?: 0L,
                consecutiveCrashes = cursor.getIntOrNull("consecutiveCrashes") ?: 0,
            ),
        )
    }
    return out
}

/** Decodes every row of the cursor as a [RouteHistoryEntry]. Closes the cursor. */
public fun Cursor.toRouteHistoryList(): List<RouteHistoryEntry> {
    val out = ArrayList<RouteHistoryEntry>(count.coerceAtLeast(0))
    forEachRow { cursor ->
        val fromName = cursor.getStringOrNull("fromRoute") ?: return@forEachRow
        val toName = cursor.getStringOrNull("toRoute") ?: return@forEachRow
        val reasonName = cursor.getStringOrNull("reason") ?: return@forEachRow
        val fromRoute = runCatching { AudioRouteKind.valueOf(fromName) }.getOrNull()
            ?: return@forEachRow
        val toRoute = runCatching { AudioRouteKind.valueOf(toName) }.getOrNull()
            ?: return@forEachRow
        val reason = runCatching { RouteTransitionReason.valueOf(reasonName) }.getOrNull()
            ?: return@forEachRow
        out.add(
            RouteHistoryEntry(
                id = cursor.getLongOrNull("id") ?: 0L,
                transitionEpochMs = cursor.getLongOrNull("transitionEpochMs") ?: 0L,
                fromRoute = fromRoute,
                toRoute = toRoute,
                reason = reason,
                audioDeviceId = cursor.getIntOrNull("audioDeviceId"),
                originMarker = cursor.getStringOrNull("originMarker").orEmpty(),
            ),
        )
    }
    return out
}

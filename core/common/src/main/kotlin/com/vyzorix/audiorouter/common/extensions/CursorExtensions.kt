package com.vyzorix.audiorouter.common.extensions

import android.database.Cursor

/**
 * Generic `Cursor` helpers shared by the daemon.
 *
 * Note: `doc/VyzorixAudioRouter_RepoTree.md` originally listed
 * `toCrashEventList()` / `toRouteHistoryList()` as members of this file.
 * Those typed conversions cannot live in `core/common` because the
 * `CrashEvent` / `RouteHistoryEntry` types are owned by `core/data`, and
 * the layered dependency rule (`core/common` MUST NOT depend on
 * `core/data`) forbids the inverse. The typed conversions live in
 * `core/data/extensions/RoomCursorExtensions.kt`; this file only carries
 * the generic helpers they build on.
 */

/**
 * Materialises every row of the cursor into a `Map<column, value>` and
 * closes the cursor. Used by ad-hoc debug helpers and by
 * `core/data/extensions/RoomCursorExtensions.kt` as the basis for typed
 * decoders.
 *
 * Values are coerced to the JVM type most natural for the SQLite column
 * type:
 *   - INTEGER    → Long
 *   - REAL       → Double
 *   - TEXT       → String
 *   - BLOB       → ByteArray
 *   - NULL       → null
 */
public fun Cursor.toMaps(): List<Map<String, Any?>> {
    val out = ArrayList<Map<String, Any?>>(count.coerceAtLeast(0))
    use { cursor ->
        if (!cursor.moveToFirst()) return emptyList()
        val columnCount = cursor.columnCount
        val columnNames = Array(columnCount) { cursor.getColumnName(it) }
        do {
            val row = HashMap<String, Any?>(columnCount)
            for (i in 0 until columnCount) {
                row[columnNames[i]] = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                    else -> cursor.getString(i)
                }
            }
            out.add(row)
        } while (cursor.moveToNext())
    }
    return out
}

/**
 * Iterates each row of the cursor, invoking [block] with the same `Cursor`
 * positioned at the row. Closes the cursor on completion.
 *
 * Use this for streaming row-by-row decoding when materialising every row
 * to a `Map` is wasteful.
 */
public inline fun Cursor.forEachRow(block: (Cursor) -> Unit) {
    use { cursor ->
        if (!cursor.moveToFirst()) return
        do {
            block(cursor)
        } while (cursor.moveToNext())
    }
}

/** Returns the value at [columnName] as a `Long?`, treating `NULL` as `null`. */
public fun Cursor.getLongOrNull(columnName: String): Long? {
    val idx = getColumnIndex(columnName)
    if (idx < 0 || isNull(idx)) return null
    return getLong(idx)
}

/** Returns the value at [columnName] as a `String?`, treating `NULL` as `null`. */
public fun Cursor.getStringOrNull(columnName: String): String? {
    val idx = getColumnIndex(columnName)
    if (idx < 0 || isNull(idx)) return null
    return getString(idx)
}

/** Returns the value at [columnName] as an `Int?`, treating `NULL` as `null`. */
public fun Cursor.getIntOrNull(columnName: String): Int? {
    val idx = getColumnIndex(columnName)
    if (idx < 0 || isNull(idx)) return null
    return getInt(idx)
}

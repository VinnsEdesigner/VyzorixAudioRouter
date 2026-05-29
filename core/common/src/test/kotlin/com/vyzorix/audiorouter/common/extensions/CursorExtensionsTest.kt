package com.vyzorix.audiorouter.common.extensions

import android.database.MatrixCursor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CursorExtensionsTest {

    @Test
    fun to_maps_emits_one_map_per_row() {
        val cursor = MatrixCursor(arrayOf("id", "name", "score"))
        cursor.addRow(arrayOf(1L, "alpha", 3.14))
        cursor.addRow(arrayOf(2L, "beta", 2.71))

        val rows = cursor.toMaps()
        assertEquals(2, rows.size)
        assertEquals(1L, rows[0]["id"])
        assertEquals("alpha", rows[0]["name"])
        assertEquals(3.14, rows[0]["score"])
        assertEquals("beta", rows[1]["name"])
    }

    @Test
    fun to_maps_handles_empty_cursor() {
        val cursor = MatrixCursor(arrayOf("id"))
        assertEquals(0, cursor.toMaps().size)
    }

    @Test
    fun get_or_null_helpers_handle_missing_column() {
        val cursor = MatrixCursor(arrayOf("id", "label"))
        cursor.addRow(arrayOf<Any?>(7L, null))
        cursor.moveToFirst()
        assertEquals(7L, cursor.getLongOrNull("id"))
        assertEquals(7, cursor.getIntOrNull("id"))
        assertNull(cursor.getStringOrNull("label"))
        assertNull(cursor.getLongOrNull("missing_column"))
    }

    @Test
    fun for_each_row_iterates_in_order() {
        val cursor = MatrixCursor(arrayOf("v"))
        cursor.addRow(arrayOf(1L))
        cursor.addRow(arrayOf(2L))
        cursor.addRow(arrayOf(3L))
        val collected = mutableListOf<Long>()
        cursor.forEachRow { c -> collected.add(c.getLong(0)) }
        assertEquals(listOf(1L, 2L, 3L), collected)
    }
}

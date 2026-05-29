package com.vyzorix.audiorouter.data.extensions

import android.database.MatrixCursor
import com.vyzorix.audiorouter.common.enums.CrashType
import com.vyzorix.audiorouter.data.entity.AudioRouteKind
import com.vyzorix.audiorouter.data.entity.RouteTransitionReason
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomCursorExtensionsTest {

    @Test
    fun to_crash_event_list_decodes_typed_rows() {
        val cursor = MatrixCursor(
            arrayOf(
                "id",
                "epochMs",
                "crashType",
                "signature",
                "stackHead",
                "processUptimeMs",
                "consecutiveCrashes",
            ),
        )
        cursor.addRow(arrayOf(1L, 1_700_000_000L, "NATIVE_FAILURE", "libfoo.so", "stack", 1L, 1))
        cursor.addRow(arrayOf(2L, 1_700_000_010L, "APP_BUG", "java.lang.NPE", "trace", 2L, 2))

        val rows = cursor.toCrashEventList()
        assertEquals(2, rows.size)
        assertEquals(CrashType.NATIVE_FAILURE, rows[0].crashType)
        assertEquals(CrashType.APP_BUG, rows[1].crashType)
    }

    @Test
    fun to_crash_event_list_skips_rows_with_unknown_enum_string() {
        val cursor = MatrixCursor(
            arrayOf(
                "id",
                "epochMs",
                "crashType",
                "signature",
                "stackHead",
                "processUptimeMs",
                "consecutiveCrashes",
            ),
        )
        cursor.addRow(arrayOf(1L, 1L, "APP_BUG", "sig", "head", 1L, 0))
        cursor.addRow(arrayOf(2L, 2L, "TOTALLY_UNKNOWN_TYPE", "sig", "head", 2L, 1))
        val rows = cursor.toCrashEventList()
        assertEquals(1, rows.size)
        assertEquals(CrashType.APP_BUG, rows[0].crashType)
    }

    @Test
    fun to_route_history_list_decodes_typed_rows() {
        val cursor = MatrixCursor(
            arrayOf(
                "id",
                "transitionEpochMs",
                "fromRoute",
                "toRoute",
                "reason",
                "audioDeviceId",
                "originMarker",
            ),
        )
        cursor.addRow(arrayOf(1L, 1_700_000_000L, "WIRED_HEADSET", "SPEAKER", "FORCE_LOOP_REASSERT", 42, "engine"))
        val rows = cursor.toRouteHistoryList()
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(AudioRouteKind.WIRED_HEADSET, row.fromRoute)
        assertEquals(AudioRouteKind.SPEAKER, row.toRoute)
        assertEquals(RouteTransitionReason.FORCE_LOOP_REASSERT, row.reason)
        assertEquals(42, row.audioDeviceId)
    }
}

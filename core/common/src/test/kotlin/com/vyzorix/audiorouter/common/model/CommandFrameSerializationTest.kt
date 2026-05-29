package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandFrameSerializationTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = false }

    /**
     * The canonical CommandFrame example from doc/COMMAND_SECURITY.md §2.
     * This test pins the wire format — any rename or omission would have to be
     * an intentional, doc-driven change.
     */
    private val canonicalFrameJson = """
        {
          "transactionId": "f7893a2-bcd0-4e12",
          "deviceId": "DEVICE-001",
          "action": "REINIT_PROJECTION",
          "timestampMs": 1730000000000,
          "nonce": "0123456789abcdef",
          "params": "{}",
          "hmac": "9f3a1bc2d4e5678901234567890abcdef1234567890abcdef1234567890abcdef"
        }
    """.trimIndent()

    @Test
    fun decodes_canonical_doc_example() {
        val frame = json.decodeFromString(CommandFrame.serializer(), canonicalFrameJson)

        assertEquals("f7893a2-bcd0-4e12", frame.transactionId)
        assertEquals("DEVICE-001", frame.deviceId)
        assertEquals("REINIT_PROJECTION", frame.action)
        assertEquals(1730000000000L, frame.timestampMs)
        assertEquals("0123456789abcdef", frame.nonce)
        assertEquals("{}", frame.params)
        assertEquals(
            "9f3a1bc2d4e5678901234567890abcdef1234567890abcdef1234567890abcdef",
            frame.hmac,
        )
    }

    @Test
    fun round_trip_preserves_all_fields() {
        val original = CommandFrame(
            transactionId = "txn-42",
            deviceId = "DEVICE-A",
            action = "FORCE_SPEAKER",
            timestampMs = 1_730_000_000_000L,
            nonce = "deadbeef",
            params = "{\"reason\":\"manual_test\"}",
            hmac = "0".repeat(64),
        )
        val encoded = json.encodeToString(CommandFrame.serializer(), original)
        val decoded = json.decodeFromString(CommandFrame.serializer(), encoded)
        assertEquals(original, decoded)
    }
}

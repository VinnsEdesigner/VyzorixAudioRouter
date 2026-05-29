package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateInfoSerializationTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = false }

    /**
     * Verbatim payload produced by the mock server's testdata/version.json.
     * If this test ever fails, the device-side parser and the mock-server response
     * have drifted and Layer 7 will break in Phase 1.
     */
    private val mockServerVersionJson = """
        {
          "version": "1.0.0-mock",
          "version_code": 1,
          "apk_filename": "vyzorix-audiorouter-mock.apk",
          "apk_sha256": "17819e3edfe86177012ca292b5b4c9db3700122f851b9c4bbd2d3dc0ec00ed20",
          "apk_size_bytes": 9,
          "release_notes": "Mock release served by cmd/mockserver. Do NOT deploy to a real device."
        }
    """.trimIndent()

    @Test
    fun decodes_mock_server_version_json() {
        val info = json.decodeFromString(UpdateInfo.serializer(), mockServerVersionJson)

        assertEquals("1.0.0-mock", info.version)
        assertEquals(1, info.versionCode)
        assertEquals("vyzorix-audiorouter-mock.apk", info.apkFilename)
        assertEquals(
            "17819e3edfe86177012ca292b5b4c9db3700122f851b9c4bbd2d3dc0ec00ed20",
            info.apkSha256,
        )
        assertEquals(9L, info.apkSizeBytes)
        assertEquals(
            "Mock release served by cmd/mockserver. Do NOT deploy to a real device.",
            info.releaseNotes,
        )
    }

    @Test
    fun round_trip_preserves_all_fields() {
        val original = UpdateInfo(
            version = "2.4.1",
            versionCode = 241,
            apkFilename = "vyzorix-audiorouter-2.4.1.apk",
            apkSha256 = "abc123",
            apkSizeBytes = 12_345_678L,
            releaseNotes = "Fixes phantom-headset detection on Nokia C22.",
        )
        val encoded = json.encodeToString(UpdateInfo.serializer(), original)
        val decoded = json.decodeFromString(UpdateInfo.serializer(), encoded)
        assertEquals(original, decoded)
    }
}

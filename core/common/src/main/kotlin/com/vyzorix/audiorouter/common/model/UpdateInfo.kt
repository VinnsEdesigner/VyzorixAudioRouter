package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response payload returned by `GET /api/v1/version` on the update server.
 *
 * Field names match the JSON shape produced by the mock server's
 * `cmd/mockserver/testdata/version.json` and documented in
 * UPDATE_MECHANISM.md §2. Snake_case on the wire, camelCase in Kotlin.
 */
@Serializable
public data class UpdateInfo(
    @SerialName("version") val version: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("apk_filename") val apkFilename: String,
    @SerialName("apk_sha256") val apkSha256: String,
    @SerialName("apk_size_bytes") val apkSizeBytes: Long,
    @SerialName("release_notes") val releaseNotes: String,
)

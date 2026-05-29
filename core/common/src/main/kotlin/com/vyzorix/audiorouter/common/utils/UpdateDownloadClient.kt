// UpdateDownloadClient — resumable HTTP downloader with SHA-256 verification.
//
// Used by Layer 5+'s `UpdateInstaller` to fetch APK / patch bundles from
// the update server. Uses `java.net.HttpURLConnection` only — no OkHttp /
// Retrofit dependency. Resume is implemented via the `Range:` header;
// SHA-256 is computed incrementally as bytes are written so the daemon
// never has to hold the full file in memory.

package com.vyzorix.audiorouter.common.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Streaming progress callback for [UpdateDownloadClient.download]. */
public fun interface DownloadProgressListener {
    public fun onProgress(bytesDownloaded: Long, totalBytes: Long)
}

/**
 * Outcome of a download attempt.
 */
public sealed interface DownloadResult {
    public data class Success(
        public val file: File,
        public val sha256Hex: String,
        public val bytesDownloaded: Long,
    ) : DownloadResult

    public data class ChecksumMismatch(
        public val expectedSha256Hex: String,
        public val actualSha256Hex: String,
    ) : DownloadResult

    public data class HttpError(
        public val responseCode: Int,
        public val message: String,
    ) : DownloadResult

    public data class IoError(public val cause: IOException) : DownloadResult
}

/**
 * Configuration knobs for [UpdateDownloadClient].
 */
public data class UpdateDownloadConfig(
    public val connectTimeoutMs: Int = 10_000,
    public val readTimeoutMs: Int = 30_000,
    /** Buffer size used for the streaming copy; defaults to 64 KiB. */
    public val streamingBufferBytes: Int = 64 * 1024,
)

/** Streaming downloader with `Range:` resume and SHA-256 verify. */
public class UpdateDownloadClient(
    private val config: UpdateDownloadConfig = UpdateDownloadConfig(),
) {

    /**
     * Download [url] to [destination], appending if the file already
     * exists (resume via `Range`). When [expectedSha256Hex] is non-null,
     * the downloaded file is checked against it and
     * [DownloadResult.ChecksumMismatch] is returned on mismatch.
     *
     * The function is blocking; callers must wrap on the appropriate
     * dispatcher (`AppDispatchers.io`).
     */
    public fun download(
        url: String,
        destination: File,
        expectedSha256Hex: String? = null,
        progressListener: DownloadProgressListener? = null,
    ): DownloadResult {
        val existingBytes = if (destination.exists()) destination.length() else 0L
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
        } catch (e: IOException) {
            return DownloadResult.IoError(e)
        }

        try {
            val responseCode = connection.responseCode
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            val isFull = responseCode == HttpURLConnection.HTTP_OK
            if (!isFull && !isPartial) {
                return DownloadResult.HttpError(
                    responseCode = responseCode,
                    message = connection.responseMessage ?: "",
                )
            }
            val contentLength = connection.contentLengthLong
            val resumeFromBytes = if (isPartial) existingBytes else 0L
            // Recompute hash from scratch on a 200 response; resume keeps the
            // existing bytes on disk and continues hashing.
            val digest = MessageDigest.getInstance("SHA-256")
            val totalBytes = if (contentLength >= 0L) {
                resumeFromBytes + contentLength
            } else {
                -1L
            }
            return doStreamingCopy(
                connection = connection,
                destination = destination,
                resumeFromBytes = resumeFromBytes,
                totalBytes = totalBytes,
                digest = digest,
                expectedSha256Hex = expectedSha256Hex,
                progressListener = progressListener,
            )
        } catch (e: IOException) {
            return DownloadResult.IoError(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun doStreamingCopy(
        connection: HttpURLConnection,
        destination: File,
        resumeFromBytes: Long,
        totalBytes: Long,
        digest: MessageDigest,
        expectedSha256Hex: String?,
        progressListener: DownloadProgressListener?,
    ): DownloadResult {
        destination.parentFile?.mkdirs()
        // For a partial-content resume we must hash the existing bytes first
        // so the SHA-256 covers the whole file. For a 200 response we discard
        // the existing file.
        if (resumeFromBytes > 0L) {
            RandomAccessFile(destination, "r").use { raf ->
                val buffer = ByteArray(config.streamingBufferBytes)
                var remaining = resumeFromBytes
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        } else if (destination.exists()) {
            destination.delete()
        }

        val output: FileOutputStream = if (resumeFromBytes > 0L) {
            FileOutputStream(destination, /* append = */ true)
        } else {
            FileOutputStream(destination, /* append = */ false)
        }

        var bytesWritten = resumeFromBytes
        output.use { stream ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(config.streamingBufferBytes)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    stream.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesWritten += read.toLong()
                    progressListener?.onProgress(bytesWritten, totalBytes)
                }
                stream.flush()
            }
        }

        val actualSha256 = HexCodec.encode(digest.digest())
        if (expectedSha256Hex != null && !actualSha256.equals(expectedSha256Hex, ignoreCase = true)) {
            return DownloadResult.ChecksumMismatch(
                expectedSha256Hex = expectedSha256Hex,
                actualSha256Hex = actualSha256,
            )
        }
        return DownloadResult.Success(
            file = destination,
            sha256Hex = actualSha256,
            bytesDownloaded = bytesWritten,
        )
    }
}

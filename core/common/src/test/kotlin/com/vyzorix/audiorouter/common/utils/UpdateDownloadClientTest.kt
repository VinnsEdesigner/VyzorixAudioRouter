package com.vyzorix.audiorouter.common.utils

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests use a tiny raw-socket HTTP server (no `com.sun.net.httpserver`, which is
 * absent from the Android-aware Kotlin test classpath). The server speaks just
 * enough HTTP/1.0 to satisfy [UpdateDownloadClient]'s use cases.
 */
class UpdateDownloadClientTest {

    private lateinit var workDir: File
    private lateinit var server: TestHttpServer

    @Before
    fun setUp() {
        workDir = File.createTempFile("vyzorix-update", null).also {
            it.delete()
            it.mkdirs()
        }
        server = TestHttpServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
        workDir.deleteRecursively()
    }

    private fun urlFor(path: String): String =
        "http://127.0.0.1:${server.port}$path"

    private fun sha256Hex(bytes: ByteArray): String =
        HexCodec.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun `download succeeds and returns the SHA-256 of the bytes`() {
        val payload = ByteArray(8 * 1024) { (it and 0xFF).toByte() }
        server.handler = TestHttpServer.Handler { _, _ -> TestHttpServer.Response(200, payload) }
        val destination = File(workDir, "out.bin")
        val result = UpdateDownloadClient().download(
            url = urlFor("/payload"),
            destination = destination,
            expectedSha256Hex = sha256Hex(payload),
        )
        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payload.size.toLong(), success.bytesDownloaded)
        assertEquals(sha256Hex(payload), success.sha256Hex)
        assertEquals(payload.size.toLong(), destination.length())
    }

    @Test
    fun `download reports a checksum mismatch when the expected hash is wrong`() {
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        server.handler = TestHttpServer.Handler { _, _ -> TestHttpServer.Response(200, payload) }
        val destination = File(workDir, "wrong.bin")
        val result = UpdateDownloadClient().download(
            url = urlFor("/wrong"),
            destination = destination,
            expectedSha256Hex = "00".repeat(32),
        )
        assertTrue(result is DownloadResult.ChecksumMismatch)
    }

    @Test
    fun `download reports HttpError on non-2xx response`() {
        server.handler = TestHttpServer.Handler { _, _ ->
            TestHttpServer.Response(404, byteArrayOf(), reasonPhrase = "Not Found")
        }
        val destination = File(workDir, "missing.bin")
        val result = UpdateDownloadClient().download(urlFor("/missing"), destination)
        assertTrue(result is DownloadResult.HttpError)
        val error = result as DownloadResult.HttpError
        assertEquals(404, error.responseCode)
    }

    @Test
    fun `download resumes from existing bytes when Range is honoured`() {
        val payload = ByteArray(4 * 1024) { (it and 0xFF).toByte() }
        server.handler = TestHttpServer.Handler { _, rangeHeader ->
            if (rangeHeader != null) {
                val startBytes = rangeHeader.removePrefix("bytes=").substringBefore("-").toInt()
                val slice = payload.copyOfRange(startBytes, payload.size)
                TestHttpServer.Response(
                    statusCode = 206,
                    body = slice,
                    extraHeaders = mapOf(
                        "Content-Range" to "bytes $startBytes-${payload.size - 1}/${payload.size}",
                    ),
                )
            } else {
                TestHttpServer.Response(200, payload)
            }
        }
        val destination = File(workDir, "resume.bin")
        destination.writeBytes(payload.copyOfRange(0, 1024))
        val result = UpdateDownloadClient().download(
            url = urlFor("/resume"),
            destination = destination,
            expectedSha256Hex = sha256Hex(payload),
        )
        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payload.size.toLong(), success.bytesDownloaded)
        assertEquals(sha256Hex(payload), success.sha256Hex)
    }
}

/**
 * Minimal HTTP/1.0 server for [UpdateDownloadClient] tests.
 *
 * Reads the request line + headers, calls [handler] with the path and Range
 * header (if any), then writes a single response and closes the socket.
 */
internal class TestHttpServer : AutoCloseable {
    private val socket = ServerSocket(0)
    val port: Int = socket.localPort

    @Volatile var handler: Handler = Handler { _, _ -> Response(500, "no handler".toByteArray()) }

    private val acceptThread: AtomicReference<Thread?> = AtomicReference(null)
    @Volatile private var running = true

    fun start() {
        val t = thread(name = "TestHttpServer-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
        acceptThread.set(t)
    }

    override fun close() {
        running = false
        try { socket.close() } catch (_: Exception) {}
        acceptThread.get()?.join(500)
    }

    private fun serve(client: Socket) {
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }
            val response = handler.handle(path, rangeHeader)
            val statusLine = "HTTP/1.0 ${response.statusCode} ${response.reasonPhrase ?: ""}\r\n"
            val baseHeaders = buildString {
                append(statusLine)
                append("Content-Length: ${response.body.size}\r\n")
                append("Connection: close\r\n")
                response.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            val out = it.getOutputStream()
            out.write(baseHeaders.toByteArray(Charsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
        }
    }

    fun interface Handler {
        fun handle(path: String, rangeHeader: String?): Response
    }

    data class Response(
        val statusCode: Int,
        val body: ByteArray,
        val reasonPhrase: String? = null,
        val extraHeaders: Map<String, String> = emptyMap(),
    )
}

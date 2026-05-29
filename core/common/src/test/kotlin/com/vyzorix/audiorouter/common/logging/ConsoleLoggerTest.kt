package com.vyzorix.audiorouter.common.logging

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleLoggerTest {

    private val stdoutBuf = ByteArrayOutputStream()
    private val stderrBuf = ByteArrayOutputStream()
    private val fixedNow = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    private fun logger() = ConsoleLogger(
        tagPrefix = "Test/",
        now = { fixedNow },
        stdout = PrintStream(stdoutBuf),
        stderr = PrintStream(stderrBuf),
    )

    @Test
    fun info_writes_to_stdout_with_timestamped_format() {
        logger().info("MyTag", "hello world")
        val out = stdoutBuf.toString().trim()
        assertEquals(
            "2023-11-14T22:13:20Z I/Test/MyTag: hello world",
            out,
        )
        assertEquals("", stderrBuf.toString())
    }

    @Test
    fun warn_writes_to_stderr() {
        logger().warn("Tag", "uh oh")
        assertEquals("", stdoutBuf.toString())
        assertTrue("uh oh" in stderrBuf.toString())
        assertTrue("W/Test/Tag" in stderrBuf.toString())
    }

    @Test
    fun error_with_throwable_appends_indented_stack_trace() {
        val ex = RuntimeException("boom")
        logger().error("Tag", "failed", ex)
        val out = stderrBuf.toString()
        assertTrue("E/Test/Tag: failed" in out)
        assertTrue("java.lang.RuntimeException: boom" in out)
        // Stack-trace lines must be indented (4 spaces prepended to every line).
        assertTrue("    java.lang.RuntimeException: boom" in out)
        // ConsoleLoggerTest itself appears in the trace; use it to confirm at-lines exist.
        assertTrue("ConsoleLoggerTest" in out)
    }
}

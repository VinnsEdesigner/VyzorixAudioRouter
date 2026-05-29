package com.vyzorix.audiorouter.common.logging

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileLoggerTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun writes_a_line_per_call() {
        tempFolder.create()
        val logFile = tempFolder.newFile("daemon.log")
        val logger = FileLogger(file = logFile)
        logger.info("Boot", "init complete")
        logger.warn("Route", "drift detected")
        val lines = logFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("init complete"))
        assertTrue(lines[1].contains("drift detected"))
    }

    @Test
    fun rolls_when_file_exceeds_cap() {
        tempFolder.create()
        val logFile = tempFolder.newFile("daemon-roll.log")
        // Small cap forces an immediate roll on the second write.
        val logger = FileLogger(file = logFile, maxFileSizeBytes = 32, maxRolledFiles = 4)
        logger.info("A", "x".repeat(64))
        logger.info("B", "y".repeat(64))
        val rolled = logFile.parentFile!!.listFiles { f ->
            f.name.startsWith("daemon-roll.log.") && f.name.endsWith(".rolled")
        }
        assertTrue(rolled != null && rolled.isNotEmpty(), "expected a rolled file to be created")
        // Current log file still exists with the latest line.
        assertTrue(logFile.exists())
    }

    @Test
    fun prune_keeps_at_most_max_rolled_files() {
        tempFolder.create()
        val logFile = tempFolder.newFile("daemon-prune.log")
        val logger = FileLogger(file = logFile, maxFileSizeBytes = 8, maxRolledFiles = 2)
        repeat(6) { i ->
            logger.info("tag-$i", "payload-".repeat(8))
        }
        val rolled = logFile.parentFile!!.listFiles { f ->
            f.name.startsWith("daemon-prune.log.") && f.name.endsWith(".rolled")
        } ?: emptyArray()
        assertTrue(rolled.size <= 2, "expected pruning to keep at most 2 rolled files, got ${rolled.size}")
    }
}

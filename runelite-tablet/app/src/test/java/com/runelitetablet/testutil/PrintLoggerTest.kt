package com.runelitetablet.testutil

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrintLoggerTest {
    private lateinit var logger: PrintLogger

    @Before
    fun setup() {
        logger = PrintLogger()
    }

    @Test
    fun `captures info entries`() {
        logger.i("TAG", "message")
        assertEquals(1, logger.entries.size)
        assertEquals("INFO", logger.entries[0].level)
        assertEquals("TAG", logger.entries[0].tag)
        assertEquals("message", logger.entries[0].message)
    }

    @Test
    fun `captures error with throwable`() {
        val ex = RuntimeException("boom")
        logger.e("ERR", "failed", ex)
        assertEquals(1, logger.entries.size)
        assertEquals("ERROR", logger.entries[0].level)
        assertEquals(ex, logger.entries[0].throwable)
    }

    @Test
    fun `convenience methods use correct tags`() {
        logger.lifecycle("test")
        logger.step("sid", "test")
        logger.cmd(42, "test")
        logger.http("test")
        logger.perf("test")

        assertEquals("LIFECYCLE", logger.entries[0].tag)
        assertEquals("STEP", logger.entries[1].tag)
        assertEquals("CMD", logger.entries[2].tag)
        assertEquals("HTTP", logger.entries[3].tag)
        assertEquals("PERF", logger.entries[4].tag)
    }

    @Test
    fun `entriesWithTag filters correctly`() {
        logger.i("A", "one")
        logger.i("B", "two")
        logger.i("A", "three")
        assertEquals(2, logger.entriesWithTag("A").size)
    }

    @Test
    fun `errorsOnly filters correctly`() {
        logger.i("A", "info")
        logger.w("A", "warn")
        logger.e("A", "error")
        assertEquals(1, logger.errorsOnly().size)
    }

    @Test
    fun `clear removes all entries`() {
        logger.i("A", "test")
        logger.clear()
        assertTrue(logger.entries.isEmpty())
    }
}

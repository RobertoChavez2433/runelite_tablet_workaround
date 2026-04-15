package com.runelitetablet.perf

import com.runelitetablet.testutil.PrintLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirglEnvironmentProfilerTest {

    private val logger = PrintLogger()
    private val profiler = VirglEnvironmentProfiler(logger)

    @Test
    fun `parseOutput extracts all fields from complete output`() {
        val output = listOf(
            "VIRGL_ENV: timestamp=1713100000",
            "VIRGL_ENV: env_GALLIUM_DRIVER=virpipe",
            "VIRGL_ENV: env_MESA_GL_VERSION_OVERRIDE=3.1",
            "VIRGL_ENV: env_MESA_NO_ERROR=1",
            "VIRGL_ENV: virgl_pid=26636",
            "VIRGL_ENV: virgl_cpu=5 threads=4 rss_kb=32768",
            "VIRGL_ENV: socket=/tmp/.virgl_test exists=true type=socket",
            "VIRGL_ENV: gpu_clock_13000000.mali=750000000",
            "VIRGL_ENV: done"
        )

        val profile = profiler.parseOutput(output)
        assertEquals("virpipe", profile.envVars["GALLIUM_DRIVER"])
        assertEquals("3.1", profile.envVars["MESA_GL_VERSION_OVERRIDE"])
        assertEquals("1", profile.envVars["MESA_NO_ERROR"])
        assertEquals(3, profile.envVars.size)
        assertEquals("26636", profile.virglPid)
        assertEquals("5", profile.virglCpu)
        assertEquals("4", profile.virglThreads)
        assertEquals("32768", profile.virglRssKb)
        assertEquals("/tmp/.virgl_test", profile.socketPath)
        assertEquals("750000000", profile.gpuClocks["13000000.mali"])
    }

    @Test
    fun `parseOutput handles missing VirGL server`() {
        val output = listOf(
            "VIRGL_ENV: timestamp=1713100000",
            "VIRGL_ENV: virgl_pid=none",
            "VIRGL_ENV: done"
        )

        val profile = profiler.parseOutput(output)
        assertEquals("none", profile.virglPid)
        assertTrue(profile.envVars.isEmpty())
    }

    @Test
    fun `parseOutput ignores non-prefixed lines`() {
        val output = listOf(
            "some random output",
            "VIRGL_ENV: env_GALLIUM_DRIVER=virpipe",
            "another random line"
        )

        val profile = profiler.parseOutput(output)
        assertEquals(1, profile.envVars.size)
    }

    @Test
    fun `parseOutput logs summary`() {
        val output = listOf(
            "VIRGL_ENV: virgl_pid=100",
            "VIRGL_ENV: env_GALLIUM_DRIVER=virpipe"
        )

        profiler.parseOutput(output)
        val perfLogs = logger.entriesWithTag("PERF")
        assertTrue(perfLogs.any { it.message.contains("VIRGL_ENV:") && it.message.contains("pid=100") })
    }
}

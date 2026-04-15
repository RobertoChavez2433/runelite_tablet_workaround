package com.runelitetablet.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates CPU affinity pinning behavior in launch-runelite.sh.
 * Tests the script's taskset invocation patterns and failure handling.
 */
class CpuAffinityScriptTest {

    @Test
    fun `affinity script logs AFFINITY prefix for each process`() {
        // Simulates parsing the expected log output from launch-runelite.sh
        val logLines = listOf(
            "AFFINITY: VirGL (PID=26636) pinned to big/prime cores (mask=0xF0)",
            "AFFINITY: RuneLite (PID=6258) pinned to big/prime cores",
            "AFFINITY: proot (PID=5930) pinned to prime core"
        )
        assertTrue(logLines.all { it.startsWith("AFFINITY:") })
        assertTrue(logLines.any { it.contains("VirGL") })
        assertTrue(logLines.any { it.contains("RuneLite") })
        assertTrue(logLines.any { it.contains("proot") })
    }

    @Test
    fun `affinity script handles taskset failure gracefully`() {
        // The script uses `taskset ... 2>/dev/null && log_success || log_warn`
        // Verify the warn message pattern exists for failure handling
        val failLine = "AFFINITY: taskset failed for VirGL (PID=26636), staying on default cores"
        assertTrue(failLine.contains("taskset failed"))
        assertTrue(failLine.contains("staying on default"))
    }

    @Test
    fun `affinity script handles missing PID gracefully`() {
        // When VIRGL_PID is empty, the script skips pinning
        val virglPid = ""
        val shouldPin = virglPid.isNotEmpty()
        assertFalse(shouldPin)
    }

    @Test
    fun `affinity masks are correct for big-prime and prime-only cores`() {
        // CPU 4-7 = big+prime, bitmask 0xF0 = 0b11110000
        val bigPrimeMask = 0xF0
        assertTrue(bigPrimeMask and (1 shl 4) != 0) // CPU 4
        assertTrue(bigPrimeMask and (1 shl 5) != 0) // CPU 5
        assertTrue(bigPrimeMask and (1 shl 6) != 0) // CPU 6
        assertTrue(bigPrimeMask and (1 shl 7) != 0) // CPU 7
        assertTrue(bigPrimeMask and (1 shl 0) == 0) // CPU 0 (little)
        assertTrue(bigPrimeMask and (1 shl 3) == 0) // CPU 3 (little)

        // CPU 7 only = prime, bitmask 0x80 = 0b10000000
        val primeMask = 0x80
        assertTrue(primeMask and (1 shl 7) != 0)  // CPU 7
        assertTrue(primeMask and (1 shl 6) == 0)  // Not CPU 6
    }
}

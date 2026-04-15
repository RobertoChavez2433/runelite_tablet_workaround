package com.runelitetablet.perf

import com.runelitetablet.testutil.PrintLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PtraceOverheadEstimatorTest {

    private lateinit var logger: PrintLogger
    private lateinit var procReader: FakeProcReader

    @Before
    fun setUp() {
        logger = PrintLogger()
        procReader = FakeProcReader()
    }

    @Test
    fun `parseUtimeStime extracts fields correctly`() {
        val stat = "6258 (java) S 1 6258 6258 0 -1 4194304 12345 0 0 0 49294 57670 0 0 20 0 42 0 123456 0 0 0 0 0 0 0 0 0 0 0 0 0 17 4 0 0 0 0 0"
        val result = PtraceOverheadEstimator.parseUtimeStime(stat)!!
        assertEquals(49294L, result.first)  // utime
        assertEquals(57670L, result.second) // stime
    }

    @Test
    fun `parseUtimeStime handles comm with spaces`() {
        val stat = "1234 (my cool app) S 1 1234 1234 0 -1 0 0 0 0 0 100 200 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"
        val result = PtraceOverheadEstimator.parseUtimeStime(stat)!!
        assertEquals(100L, result.first)
        assertEquals(200L, result.second)
    }

    @Test
    fun `parseUtimeStime returns null for garbage`() {
        assertNull(PtraceOverheadEstimator.parseUtimeStime("garbage"))
        assertNull(PtraceOverheadEstimator.parseUtimeStime(""))
    }

    @Test
    fun `sample computes kernel ratio correctly`() {
        procReader.stats[100] = "100 (RuneLite) S 1 100 100 0 -1 0 0 0 0 0 46 54 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"
        procReader.comms[100] = "RuneLite"

        val estimator = PtraceOverheadEstimator(logger, procReader)
        val results = estimator.sample(listOf(100))

        assertEquals(1, results.size)
        assertEquals(0.54, results[0].kernelRatio, 0.01) // 54 / (46 + 54) = 0.54
        assertTrue(results[0].ptraceImpacted) // 0.54 > 0.30
    }

    @Test
    fun `sample uses delta for subsequent samples`() {
        procReader.stats[100] = "100 (RuneLite) S 1 100 100 0 -1 0 0 0 0 0 100 100 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"
        procReader.comms[100] = "RuneLite"

        val estimator = PtraceOverheadEstimator(logger, procReader)
        estimator.sample(listOf(100)) // First sample: cumulative

        // Second sample: utime +80, stime +20. Delta ratio = 20/(80+20) = 0.20
        procReader.stats[100] = "100 (RuneLite) S 1 100 100 0 -1 0 0 0 0 0 180 120 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"

        val results = estimator.sample(listOf(100))
        assertEquals(0.20, results[0].kernelRatio, 0.01)
        assertFalse(results[0].ptraceImpacted) // 0.20 < 0.30
    }

    @Test
    fun `sample flags high kernel ratio as impacted`() {
        procReader.stats[200] = "200 (proot) S 1 200 200 0 -1 0 0 0 0 0 10 90 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"
        procReader.comms[200] = "proot"

        val estimator = PtraceOverheadEstimator(logger, procReader)
        val results = estimator.sample(listOf(200))

        assertTrue(results[0].ptraceImpacted) // 90/(10+90) = 0.90
    }

    @Test
    fun `sample skips missing PIDs`() {
        val estimator = PtraceOverheadEstimator(logger, procReader)
        assertTrue(estimator.sample(listOf(999)).isEmpty())
    }

    @Test
    fun `sample logs PTRACE entries`() {
        procReader.stats[100] = "100 (RuneLite) S 1 100 100 0 -1 0 0 0 0 0 50 50 0 0 20 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0"
        procReader.comms[100] = "RuneLite"

        val estimator = PtraceOverheadEstimator(logger, procReader)
        estimator.sample(listOf(100))

        val perfLogs = logger.entriesWithTag("PERF")
        assertTrue(perfLogs.any { it.message.contains("PTRACE:") && it.message.contains("pid=100") })
    }

    class FakeProcReader : PtraceOverheadEstimator.ProcReader {
        val stats = mutableMapOf<Int, String>()
        val comms = mutableMapOf<Int, String>()

        override fun readStat(pid: Int): String? = stats[pid]
        override fun readComm(pid: Int): String? = comms[pid]
    }
}

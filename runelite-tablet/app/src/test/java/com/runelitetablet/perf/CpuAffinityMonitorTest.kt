package com.runelitetablet.perf

import com.runelitetablet.testutil.PrintLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CpuAffinityMonitorTest {

    private lateinit var logger: PrintLogger
    private lateinit var procReader: FakeProcReader

    @Before
    fun setUp() {
        logger = PrintLogger()
        procReader = FakeProcReader()
    }

    /**
     * Build a synthetic /proc/PID/stat line with the given pid, comm, and CPU (field 39).
     * Fields 3-52 = 50 total. Processor is field 39 = index 36 after (comm).
     */
    private fun buildStatLine(pid: Int, comm: String, cpu: Int, utime: Long = 100, stime: Long = 50): String {
        // 50 fields: index 0=state(3), 11=utime(14), 12=stime(15), 36=processor(39)
        val fields = MutableList(50) { "0" }
        fields[0] = "S"            // state
        fields[1] = "1"            // ppid
        fields[11] = utime.toString() // utime (field 14)
        fields[12] = stime.toString() // stime (field 15)
        fields[36] = cpu.toString()   // processor (field 39)
        return "$pid ($comm) ${fields.joinToString(" ")}"
    }

    @Test
    fun `parseCpuFromStat extracts field 39 correctly`() {
        val stat = buildStatLine(6258, "java", 4)
        assertEquals(4, CpuAffinityMonitor.parseCpuFromStat(stat))
    }

    @Test
    fun `parseCpuFromStat handles comm with spaces`() {
        val stat = buildStatLine(1234, "my cool app", 7)
        assertEquals(7, CpuAffinityMonitor.parseCpuFromStat(stat))
    }

    @Test
    fun `parseCpuFromStat returns null for garbage input`() {
        assertNull(CpuAffinityMonitor.parseCpuFromStat("garbage"))
        assertNull(CpuAffinityMonitor.parseCpuFromStat(""))
    }

    @Test
    fun `classifyCoreType categorizes frequencies correctly`() {
        assertEquals("little", CpuAffinityMonitor.classifyCoreType(2000000))
        assertEquals("little", CpuAffinityMonitor.classifyCoreType(2100000))
        assertEquals("big", CpuAffinityMonitor.classifyCoreType(2800000))
        assertEquals("big", CpuAffinityMonitor.classifyCoreType(2900000))
        assertEquals("prime", CpuAffinityMonitor.classifyCoreType(3400000))
        assertEquals("unknown", CpuAffinityMonitor.classifyCoreType(0))
        assertEquals("unknown", CpuAffinityMonitor.classifyCoreType(-1))
    }

    @Test
    fun `sample returns affinity info for valid PIDs`() {
        procReader.stats[100] = buildStatLine(100, "RuneLite", 5)
        procReader.comms[100] = "RuneLite"
        procReader.maxFreqs[5] = 2800000L

        val monitor = CpuAffinityMonitor(logger, procReader)
        val results = monitor.sample(listOf(100))

        assertEquals(1, results.size)
        assertEquals(100, results[0].pid)
        assertEquals("RuneLite", results[0].name)
        assertEquals(5, results[0].core.cpu)
        assertEquals("big", results[0].core.type)
        assertEquals(0, results[0].migrations)
    }

    @Test
    fun `sample detects migration and increments counter`() {
        procReader.stats[100] = buildStatLine(100, "RuneLite", 5)
        procReader.comms[100] = "RuneLite"
        procReader.maxFreqs[5] = 2800000L

        val monitor = CpuAffinityMonitor(logger, procReader)
        monitor.sample(listOf(100))

        // Second sample: CPU 1 (migrated to little core)
        procReader.stats[100] = buildStatLine(100, "RuneLite", 1)
        procReader.maxFreqs[1] = 2000000L

        val results = monitor.sample(listOf(100))
        assertEquals(1, results[0].migrations)

        val migrationLogs = logger.entriesWithTag("PERF")
            .filter { it.message.contains("AFFINITY_MIGRATION") }
        assertEquals(1, migrationLogs.size)
        assertTrue(migrationLogs[0].message.contains("from=5"))
        assertTrue(migrationLogs[0].message.contains("to=1"))
    }

    @Test
    fun `sample skips missing PIDs`() {
        val monitor = CpuAffinityMonitor(logger, procReader)
        val results = monitor.sample(listOf(999))
        assertTrue(results.isEmpty())
    }

    class FakeProcReader : CpuAffinityMonitor.ProcReader {
        val stats = mutableMapOf<Int, String>()
        val comms = mutableMapOf<Int, String>()
        val maxFreqs = mutableMapOf<Int, Long>()

        override fun readStat(pid: Int): String? = stats[pid]
        override fun readComm(pid: Int): String? = comms[pid]
        override fun readMaxFreq(cpu: Int): Long = maxFreqs[cpu] ?: 0L
    }
}

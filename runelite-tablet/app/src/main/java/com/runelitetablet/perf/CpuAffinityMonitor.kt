package com.runelitetablet.perf

import com.runelitetablet.domain.logging.Logger

/**
 * Monitors CPU core affinity for pipeline processes by parsing /proc/PID/stat.
 * Tracks core migrations and classifies cores as little/big/prime by max frequency.
 */
class CpuAffinityMonitor(
    private val logger: Logger,
    private val procReader: ProcReader = DefaultProcReader
) {

    data class CoreInfo(val cpu: Int, val type: String, val freq: Long)
    data class ProcessAffinity(val pid: Int, val name: String, val core: CoreInfo, val migrations: Int)

    private val lastCore = mutableMapOf<Int, Int>()
    private val migrationCount = mutableMapOf<Int, Int>()

    fun sample(pids: List<Int>): List<ProcessAffinity> {
        return pids.mapNotNull { pid -> sampleProcess(pid) }
    }

    private fun sampleProcess(pid: Int): ProcessAffinity? {
        val stat = procReader.readStat(pid) ?: return null
        val cpu = parseCpuFromStat(stat) ?: return null
        val name = procReader.readComm(pid) ?: "unknown"
        val maxFreq = procReader.readMaxFreq(cpu)
        val coreType = classifyCoreType(maxFreq)

        val prev = lastCore[pid]
        if (prev != null && prev != cpu) {
            migrationCount[pid] = (migrationCount[pid] ?: 0) + 1
            logger.perf("AFFINITY_MIGRATION: pid=$pid from=$prev to=$cpu type=$coreType migrations=${migrationCount[pid]}")
        }
        lastCore[pid] = cpu

        val info = CoreInfo(cpu, coreType, maxFreq)
        val result = ProcessAffinity(pid, name, info, migrationCount[pid] ?: 0)
        logger.perf("AFFINITY: pid=$pid name=$name cpu=$cpu type=$coreType freq=$maxFreq")
        return result
    }

    interface ProcReader {
        fun readStat(pid: Int): String?
        fun readComm(pid: Int): String?
        fun readMaxFreq(cpu: Int): Long
    }

    object DefaultProcReader : ProcReader {
        override fun readStat(pid: Int): String? = try {
            java.io.File("/proc/$pid/stat").readText()
        } catch (_: Exception) { null }

        override fun readComm(pid: Int): String? = try {
            java.io.File("/proc/$pid/comm").readText().trim()
        } catch (_: Exception) { null }

        override fun readMaxFreq(cpu: Int): Long = try {
            java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        } catch (_: Exception) { 0L }
    }

    companion object {
        fun parseCpuFromStat(stat: String): Int? {
            // /proc/PID/stat fields are space-separated; field 39 (1-indexed) is processor
            // But field 2 (comm) can contain spaces and is wrapped in parens
            val closeParenIdx = stat.lastIndexOf(')')
            if (closeParenIdx < 0) return null
            val fields = stat.substring(closeParenIdx + 2).split(' ')
            // After (comm), field index 0 = state (field 3), so field 39 = index 36
            return fields.getOrNull(36)?.toIntOrNull()
        }

        fun classifyCoreType(maxFreqKhz: Long): String = when {
            maxFreqKhz <= 0 -> "unknown"
            maxFreqKhz <= 2100000 -> "little"
            maxFreqKhz <= 2900000 -> "big"
            else -> "prime"
        }
    }
}

package com.runelitetablet.perf

import com.runelitetablet.domain.logging.Logger

/**
 * Measures kernel time ratio (stime/utime) for pipeline processes.
 * Normal apps: 5-15% kernel time. proot-wrapped: >50%.
 * Samples /proc/PID/stat fields 14 (utime) and 15 (stime).
 */
class PtraceOverheadEstimator(
    private val logger: Logger,
    private val procReader: ProcReader = DefaultProcReader
) {

    data class OverheadSample(
        val pid: Int,
        val name: String,
        val utime: Long,
        val stime: Long,
        val kernelRatio: Double,
        val ptraceImpacted: Boolean
    )

    private val lastUtime = mutableMapOf<Int, Long>()
    private val lastStime = mutableMapOf<Int, Long>()

    fun sample(pids: List<Int>): List<OverheadSample> {
        return pids.mapNotNull { pid -> sampleProcess(pid) }
    }

    private fun sampleProcess(pid: Int): OverheadSample? {
        val stat = procReader.readStat(pid) ?: return null
        val (utime, stime) = parseUtimeStime(stat) ?: return null
        val name = procReader.readComm(pid) ?: "unknown"

        val prevU = lastUtime[pid]
        val prevS = lastStime[pid]
        lastUtime[pid] = utime
        lastStime[pid] = stime

        // Use delta for per-interval measurement
        val deltaU = if (prevU != null) utime - prevU else utime
        val deltaS = if (prevS != null) stime - prevS else stime
        val total = deltaU + deltaS
        val ratio = if (total > 0) deltaS.toDouble() / total else 0.0
        val impacted = ratio > 0.30

        val sample = OverheadSample(pid, name, deltaU, deltaS, ratio, impacted)
        logger.perf("PTRACE: pid=$pid name=$name utime=$deltaU stime=$deltaS kernel_ratio=%.2f impacted=$impacted".format(ratio))
        return sample
    }

    interface ProcReader {
        fun readStat(pid: Int): String?
        fun readComm(pid: Int): String?
    }

    object DefaultProcReader : ProcReader {
        override fun readStat(pid: Int): String? = try {
            java.io.File("/proc/$pid/stat").readText()
        } catch (_: Exception) { null }

        override fun readComm(pid: Int): String? = try {
            java.io.File("/proc/$pid/comm").readText().trim()
        } catch (_: Exception) { null }
    }

    companion object {
        /**
         * Extracts utime (field 14) and stime (field 15) from /proc/PID/stat.
         * Field 2 (comm) can contain spaces, so we find closing paren first.
         */
        fun parseUtimeStime(stat: String): Pair<Long, Long>? {
            val closeParenIdx = stat.lastIndexOf(')')
            if (closeParenIdx < 0) return null
            val fields = stat.substring(closeParenIdx + 2).split(' ')
            // After (comm): index 0 = state (field 3), utime = field 14 = index 11, stime = field 15 = index 12
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
            return utime to stime
        }
    }
}

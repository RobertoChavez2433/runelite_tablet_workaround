package com.runelitetablet.perf

import com.runelitetablet.domain.logging.Logger

/**
 * Promotes pipeline processes (VirGL, proot) from the moderate cpuset
 * (CPUs 0-3, little cores) to top-app cpuset (CPUs 0-6+, big+prime cores).
 *
 * Samsung devices restrict Termux-spawned processes to the "moderate" cpuset.
 * Uses android.os.Process.setProcessGroup() via reflection to promote PIDs.
 */
class CpuBooster(private val logger: Logger) {

    private val boostedPids = mutableSetOf<Int>()

    /**
     * Boost a specific PID to top-app scheduling group.
     * Call from the Android app process (foreground activity).
     */
    fun boostPid(pid: Int, name: String): Boolean {
        if (pid <= 0 || pid in boostedPids) return pid in boostedPids
        val before = readCpuset(pid)
        val success = trySetProcessGroup(pid, THREAD_GROUP_TOP_APP)
            || trySetProcessGroup(pid, THREAD_GROUP_FOREGROUND)
        val after = readCpuset(pid)
        if (success) {
            boostedPids.add(pid)
            logger.perf("CPU_BOOST: $name (PID=$pid) cpuset $before -> $after")
        } else {
            logger.w("CPU_BOOST", "$name (PID=$pid) failed: cpuset=$before (setProcessGroup denied)")
        }
        return success
    }

    /**
     * Boost pipeline PIDs discovered from Termux session files.
     * Called with PIDs obtained via CommandRunner (Termux IPC).
     */
    fun boostFromSessionPids(virglPid: Int, javaPid: Int, prootPid: Int = 0) {
        boostPid(virglPid, "virgl")
        boostPid(javaPid, "java")
        if (prootPid > 0) boostPid(prootPid, "proot")
    }

    private fun readCpuset(pid: Int): String = try {
        java.io.File("/proc/$pid/cpuset").readText().trim()
    } catch (_: Exception) { "unknown" }

    companion object {
        private const val THREAD_GROUP_FOREGROUND = 1
        private const val THREAD_GROUP_TOP_APP = 5

        private fun trySetProcessGroup(pid: Int, group: Int): Boolean = try {
            val method = android.os.Process::class.java.getMethod(
                "setProcessGroup", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            method.invoke(null, pid, group)
            true
        } catch (_: Exception) { false }
    }
}

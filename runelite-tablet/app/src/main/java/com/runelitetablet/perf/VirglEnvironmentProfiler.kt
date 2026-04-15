package com.runelitetablet.perf

import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.logging.Logger

/**
 * Captures and parses VirGL pipeline configuration via profile-virgl-env.sh.
 * Collects Mesa/VirGL env vars, server process state, socket health, ANGLE info,
 * and GPU clock state.
 */
class VirglEnvironmentProfiler(private val logger: Logger) {

    /** Execute the profiler script via CommandRunner and return parsed profile. */
    suspend fun run(runner: CommandRunner): VirglProfile {
        val scriptsDir = "${CommandRunner.TERMUX_HOME_PATH}/scripts"
        val result = runner.execute(commandPath = "$scriptsDir/profile-virgl-env.sh")
        return parseOutput(((result.stdout ?: "") + "\n" + (result.stderr ?: "")).lines())
    }

    data class VirglProfile(
        val envVars: Map<String, String>,
        val virglPid: String,
        val virglCpu: String,
        val virglThreads: String,
        val virglRssKb: String,
        val socketPath: String,
        val gpuClocks: Map<String, String>
    )

    fun parseOutput(lines: List<String>): VirglProfile {
        val envVars = mutableMapOf<String, String>()
        var virglPid = "none"
        var virglCpu = ""
        var virglThreads = ""
        var virglRssKb = ""
        var socketPath = ""
        val gpuClocks = mutableMapOf<String, String>()

        for (line in lines) {
            if (!line.startsWith("VIRGL_ENV:")) continue
            val payload = line.substringAfter("VIRGL_ENV:").trim()

            when {
                payload.startsWith("env_") -> {
                    val eqIdx = payload.indexOf('=')
                    if (eqIdx > 0) envVars[payload.substring(4, eqIdx)] = payload.substring(eqIdx + 1)
                }
                payload.startsWith("virgl_pid=") ->
                    virglPid = payload.substringAfter("virgl_pid=")
                payload.startsWith("virgl_cpu=") -> {
                    for (kv in payload.split(' ')) {
                        val parts = kv.split('=', limit = 2)
                        if (parts.size == 2) when (parts[0]) {
                            "virgl_cpu" -> virglCpu = parts[1]
                            "threads" -> virglThreads = parts[1]
                            "rss_kb" -> virglRssKb = parts[1]
                        }
                    }
                }
                payload.startsWith("socket=") ->
                    socketPath = payload.substringAfter("socket=").substringBefore(' ')
                payload.startsWith("gpu_clock_") -> {
                    val eqIdx = payload.indexOf('=')
                    if (eqIdx > 0) gpuClocks[payload.substring(10, eqIdx)] = payload.substring(eqIdx + 1)
                }
            }
        }

        val profile = VirglProfile(envVars, virglPid, virglCpu, virglThreads, virglRssKb, socketPath, gpuClocks)
        logger.perf("VIRGL_ENV: pid=$virglPid cpu=$virglCpu threads=$virglThreads rss=$virglRssKb envs=${envVars.size}")
        return profile
    }
}

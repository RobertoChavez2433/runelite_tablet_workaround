package com.runelitetablet.perf

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.logging.Logger

/**
 * Runs glxgears through the full proot+VirGL pipeline and measures ceiling FPS.
 * Executes benchmark-pipeline.sh via TermuxCommandRunner and parses the output.
 */
class PipelineBenchmark(private val logger: Logger) {

    /**
     * Execute the benchmark script via CommandRunner and return parsed results.
     */
    suspend fun run(runner: CommandRunner, durationSeconds: Int = 30): BenchmarkResult {
        val scriptsDir = "${CommandRunner.TERMUX_HOME_PATH}/scripts"
        val result: CommandResult = runner.execute(
            commandPath = "$scriptsDir/benchmark-pipeline.sh",
            arguments = arrayOf(durationSeconds.toString()),
            timeoutMs = (durationSeconds + 30) * 1000L
        )
        val lines = ((result.stdout ?: "") + "\n" + (result.stderr ?: "")).lines()
        return parseOutput(lines)
    }

    data class BenchmarkResult(
        val fps: Double,
        val glRenderer: String,
        val glVersion: String,
        val error: String? = null
    )

    fun parseOutput(lines: List<String>): BenchmarkResult {
        var glRenderer = ""
        var glVersion = ""
        var error: String? = null
        val fpsValues = mutableListOf<Double>()

        for (line in lines) {
            when {
                line.startsWith("BENCHMARK: gl_renderer=") ->
                    glRenderer = line.substringAfter("gl_renderer=")
                line.startsWith("BENCHMARK: gl_version=") ->
                    glVersion = line.substringAfter("gl_version=")
                line.startsWith("BENCHMARK: error=") ->
                    error = line.substringAfter("error=")
                line.contains("frames in") ->
                    parseFpsFromGlxgears(line)?.let { fpsValues.add(it) }
            }
        }

        val avgFps = if (fpsValues.isNotEmpty()) fpsValues.average() else 0.0

        val result = BenchmarkResult(avgFps, glRenderer, glVersion, error)
        logger.perf("BENCHMARK: fps=%.1f renderer=%s version=%s error=%s".format(
            result.fps, result.glRenderer, result.glVersion, result.error ?: "none"
        ))
        return result
    }

    companion object {
        /**
         * Parses "300 frames in 5.0 seconds = 60.0 FPS" format.
         */
        fun parseFpsFromGlxgears(line: String): Double? {
            val match = Regex("""(\d+) frames in [\d.]+ seconds = ([\d.]+) FPS""").find(line)
            return match?.groupValues?.get(2)?.toDoubleOrNull()
        }
    }
}

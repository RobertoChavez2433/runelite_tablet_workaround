package com.runelitetablet.setup

import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.ScriptDeployer
import com.runelitetablet.domain.setup.SetupStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Reconciles SharedPreferences cache against actual marker files in Termux.
 * Runs check-markers.sh and parses PRESENT/ABSENT/VERSION output.
 */
class MarkerReconciler(
    private val commandRunner: CommandRunner,
    private val scriptDeployer: ScriptDeployer,
    private val logger: Logger? = null
) {
    data class ReconcileResult(
        val presentKeys: Set<String>,
        val absentKeys: Set<String>,
        val versionMismatch: Boolean
    )

    suspend fun reconcile(): ReconcileResult? {
        val deployed = scriptDeployer.deployScripts()
        if (!deployed) {
            logger?.w("STEP", "reconcile: script deployment failed, keeping cached state")
            return null
        }

        return try {
            val result = commandRunner.execute(
                commandPath = scriptDeployer.getScriptPath("check-markers.sh"),
                background = true,
                timeoutMs = MARKER_CHECK_TIMEOUT_MS
            )

            if (!result.isSuccess) {
                logger?.w("STEP", "reconcile: check-markers.sh returned non-zero (${result.exitCode})")
                return null
            }

            parseOutput(result.stdout ?: "")
        } catch (e: TimeoutCancellationException) {
            logger?.w("STEP", "reconcile: timeout after ${MARKER_CHECK_TIMEOUT_MS}ms")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.w("STEP", "reconcile: exception (${e.message})")
            null
        }
    }

    companion object {
        private const val MARKER_CHECK_TIMEOUT_MS = 10_000L

        val MODULAR_STEPS = mapOf(
            SetupStep.InstallProot to Pair("step-proot", "install-proot.sh"),
            SetupStep.InstallJava to Pair("step-java", "install-java.sh"),
            SetupStep.DownloadRuneLite to Pair("step-runelite", "download-runelite.sh"),
            SetupStep.InstallGpuDrivers to Pair("step-gpu", "setup-gpu.sh")
        )

        /** Parse check-markers.sh output. Pure logic — no deps, testable standalone. */
        fun parseOutput(output: String): ReconcileResult {
            val lines = output.lines()
            val presentKeys = mutableSetOf<String>()
            val absentKeys = mutableSetOf<String>()

            val versionLine = lines.find { it.startsWith("VERSION ") }
            val markerVersion = versionLine?.removePrefix("VERSION ")?.trim() ?: "none"
            val versionMismatch = markerVersion != "none" &&
                markerVersion != SetupStateStore.CURRENT_SCRIPT_VERSION

            for (line in lines) {
                when {
                    line.startsWith("PRESENT ") -> presentKeys.add(line.removePrefix("PRESENT ").trim())
                    line.startsWith("ABSENT ") -> absentKeys.add(line.removePrefix("ABSENT ").trim())
                }
            }

            return ReconcileResult(presentKeys, absentKeys, versionMismatch)
        }
    }
}

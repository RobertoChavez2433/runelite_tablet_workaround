package com.runelitetablet.setup

import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.ScriptDeployer
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.installer.GitHubRepo
import com.runelitetablet.installer.InstallResult

/**
 * Executes individual setup steps: APK installation, modular script execution, GPU setup.
 * Reports progress via callbacks; does not own step state.
 */
class SetupStepRunner(
    private val commandRunner: CommandRunner,
    private val packageChecker: PackageChecker,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val scriptDeployer: ScriptDeployer,
    private val stateStore: SetupStateStore,
    private val logger: Logger? = null
) {
    interface Callbacks {
        fun updateOutput(message: String?)
        fun requestInstallPermission()
        fun launchIntent(intent: android.content.Intent)
    }

    suspend fun executeModularScript(scriptName: String, markerKey: String, callbacks: Callbacks): Boolean {
        if (!scriptDeployer.deployScripts() || !scriptDeployer.deployConfigs()) {
            callbacks.updateOutput("Failed to deploy scripts to Termux")
            return false
        }

        callbacks.updateOutput("Running $scriptName (this may take several minutes)...")

        val result = commandRunner.execute(
            commandPath = scriptDeployer.getScriptPath(scriptName),
            background = true,
            timeoutMs = CommandRunner.DEFAULT_TIMEOUT_MS
        )

        val completionMarker = "=== ${scriptName.removeSuffix(".sh")} complete ==="
        val scriptCompleted = result.stdout?.contains(completionMarker) == true

        if (result.isSuccess || scriptCompleted) {
            callbacks.updateOutput(result.stdout?.let { if (it.length > 2000) it.takeLast(2000) else it })
            if (!result.isSuccess) {
                logger?.w("STEP", "executeModularScript: non-zero exit (${result.exitCode}) for $scriptName but completed")
            }
            stateStore.markCompleted(markerKey)
            stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
            return true
        } else {
            val errorOutput = result.stderr ?: result.error ?: "Unknown error"
            logger?.e("STEP", "executeModularScript: $scriptName failed exitCode=${result.exitCode}")
            callbacks.updateOutput(errorOutput)
            return false
        }
    }

    suspend fun installPackage(
        repo: GitHubRepo,
        isInstalled: () -> Boolean,
        callbacks: Callbacks
    ): Boolean {
        if (isInstalled()) return true

        callbacks.updateOutput("Downloading ${repo.name}...")
        var lastEmittedPercent = -1
        val apkFile = apkDownloader.download(repo) { bytesRead, totalBytes ->
            if (totalBytes > 0) {
                val percent = (bytesRead * 100 / totalBytes).toInt()
                if (percent != lastEmittedPercent) {
                    lastEmittedPercent = percent
                    callbacks.updateOutput("Downloading ${repo.name}... $percent%")
                }
            }
        }

        callbacks.updateOutput("Installing ${repo.name}...")

        if (!apkInstaller.canInstallPackages()) {
            callbacks.requestInstallPermission()
            return false
        }

        return when (val result = apkInstaller.install(apkFile, repo.expectedPackageName)) {
            is InstallResult.Success -> {
                try { if (apkFile.exists()) apkFile.delete() } catch (_: Exception) {}
                true
            }
            is InstallResult.NeedsUserAction -> {
                callbacks.updateOutput("Install requires confirmation — please retry")
                false
            }
            is InstallResult.Failure -> {
                val message = if (result.message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    result.message.contains("signatures do not match")
                ) "Signing key conflict: please uninstall the existing version first, then tap Retry"
                else result.message
                callbacks.updateOutput(message)
                false
            }
        }
    }

    suspend fun executeGpuStep(callbacks: Callbacks): Boolean {
        if (stateStore.isCompleted("step-gpu")) return true

        if (!scriptDeployer.deployScripts()) {
            logger?.w("STEP", "executeGpuStep: script deployment failed, skipping")
            return true
        }

        callbacks.updateOutput("Installing GPU drivers (this may take several minutes)...")

        val result = commandRunner.execute(
            commandPath = scriptDeployer.getScriptPath("setup-gpu.sh"),
            background = true,
            timeoutMs = CommandRunner.DEFAULT_TIMEOUT_MS
        )

        val scriptCompleted = result.stdout?.contains("GPU_SETUP_COMPLETE") == true
        if (result.isSuccess || scriptCompleted) {
            callbacks.updateOutput(result.stdout?.let { if (it.length > 2000) it.takeLast(2000) else it })
            stateStore.markCompleted("step-gpu")
            return true
        } else {
            val errorOutput = result.stderr ?: result.error ?: "Unknown error"
            logger?.w("STEP", "executeGpuStep: GPU setup failed (non-blocking): $errorOutput")
            callbacks.updateOutput("GPU driver installation failed (software rendering will be used). Error: $errorOutput")
            stateStore.markCompleted("step-gpu")
            return true
        }
    }
}

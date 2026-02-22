package com.runelitetablet.setup

import android.content.Context
import android.content.Intent
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.installer.GitHubRepo
import com.runelitetablet.installer.InstallResult
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Callback interface to decouple SetupOrchestrator from Activity.
 * Avoids holding a direct Activity reference (leak risk).
 */
interface SetupActions {
    fun requestInstallPermission()
    fun launchIntent(intent: Intent)
}

class SetupOrchestrator(
    private val context: Context,
    private val termuxHelper: TermuxPackageHelper,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val commandRunner: TermuxCommandRunner,
    private val scriptManager: ScriptManager
) {
    @Volatile var actions: SetupActions? = null

    private val _steps = MutableStateFlow(
        SetupStep.allSteps.map { StepState(it) }
    )
    val steps: StateFlow<List<StepState>> = _steps.asStateFlow()

    private val _currentStep = MutableStateFlow<SetupStep?>(null)
    val currentStep: StateFlow<SetupStep?> = _currentStep.asStateFlow()

    private val _currentOutput = MutableStateFlow<String?>(null)
    val currentOutput: StateFlow<String?> = _currentOutput.asStateFlow()

    @Volatile private var failedStepIndex: Int = -1
    @Volatile private var setupScriptRan: Boolean = false

    suspend fun runSetup() {
        evaluateCompletedSteps()
        runSetupFrom(0)
    }

    suspend fun retryCurrentStep() {
        if (failedStepIndex < 0) return
        val index = failedStepIndex
        val stepState = _steps.value[index]

        _currentStep.value = stepState.step
        updateStepStatus(index, StepStatus.InProgress)
        _currentOutput.value = null
        failedStepIndex = -1

        try {
            val success = executeStep(stepState.step)
            if (success) {
                updateStepStatus(index, StepStatus.Completed)
                runSetupFrom(index + 1)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
            _currentOutput.value = e.message
            failedStepIndex = index
        }
    }

    fun skipToStep(step: SetupStep) {
        val index = _steps.value.indexOfFirst { it.step == step }
        if (index >= 0) {
            failedStepIndex = index
        }
    }

    suspend fun verifyPermissions(): Boolean {
        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/echo",
            arguments = arrayOf("ok"),
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
        )
        return result.isSuccess && result.stdout?.trim() == "ok"
    }

    private suspend fun runSetupFrom(startIndex: Int) {
        for (index in startIndex until _steps.value.size) {
            val stepState = _steps.value[index]
            if (stepState.status is StepStatus.Completed) continue

            _currentStep.value = stepState.step
            updateStepStatus(index, StepStatus.InProgress)
            _currentOutput.value = null

            try {
                val success = executeStep(stepState.step)
                if (success) {
                    updateStepStatus(index, StepStatus.Completed)
                } else {
                    failedStepIndex = index
                    return
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
                _currentOutput.value = e.message
                failedStepIndex = index
                return
            }
        }
        _currentStep.value = null
        _currentOutput.value = null
    }

    private suspend fun executeStep(step: SetupStep): Boolean {
        return when (step) {
            SetupStep.InstallTermux -> installPackage(GitHubRepo.TERMUX) {
                termuxHelper.isTermuxInstalled()
            }
            SetupStep.InstallTermuxX11 -> installPackage(GitHubRepo.TERMUX_X11) {
                termuxHelper.isTermuxX11Installed()
            }
            SetupStep.EnablePermissions -> handlePermissionsStep()
            SetupStep.InstallProot,
            SetupStep.InstallJava,
            SetupStep.DownloadRuneLite -> runSetupScript()
            SetupStep.VerifySetup -> runVerification()
        }
    }

    private suspend fun installPackage(
        repo: GitHubRepo,
        isInstalled: () -> Boolean
    ): Boolean {
        if (isInstalled()) return true

        _currentOutput.value = "Downloading ${repo.name}..."
        val apkFile = apkDownloader.download(repo) { bytesRead, totalBytes ->
            if (totalBytes > 0) {
                val percent = (bytesRead * 100 / totalBytes).toInt()
                _currentOutput.value = "Downloading ${repo.name}... $percent%"
            }
        }

        _currentOutput.value = "Installing ${repo.name}..."

        if (!apkInstaller.canInstallPackages()) {
            actions?.requestInstallPermission()
            updateCurrentStepStatus(StepStatus.Failed("Please allow app installs, then tap Retry"))
            return false
        }

        val result = apkInstaller.install(apkFile)
        return when (result) {
            is InstallResult.Success -> true
            is InstallResult.NeedsUserAction -> {
                _currentOutput.value = "Waiting for install confirmation..."
                actions?.launchIntent(result.intent)
                // Set to failed so user can tap Retry after confirming install
                updateCurrentStepStatus(
                    StepStatus.Failed("Tap Retry after confirming the install")
                )
                false
            }
            is InstallResult.Failure -> {
                val message = if (result.message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    result.message.contains("signatures do not match")
                ) {
                    "Signing key conflict: please uninstall the existing version first, then tap Retry"
                } else {
                    result.message
                }
                updateCurrentStepStatus(StepStatus.Failed(message))
                _currentOutput.value = message
                false
            }
        }
    }

    private suspend fun handlePermissionsStep(): Boolean {
        val alreadyWorking = verifyPermissions()
        if (alreadyWorking) return true

        val instructions = listOf(
            "Open Termux and run:\necho \"allow-external-apps=true\" >> ~/.termux/termux.properties",
            "Go to Settings > Apps > RuneLite for Tablet > Permissions > enable \"Run commands in Termux\"",
            "Go to Settings > Apps > Termux > Battery > Unrestricted"
        )

        val stepIndex = _steps.value.indexOfFirst { it.step == SetupStep.EnablePermissions }
        updateStepStatus(stepIndex, StepStatus.ManualAction(instructions))
        _currentOutput.value =
            "Manual configuration required. Complete the steps above, then tap Verify."

        failedStepIndex = stepIndex
        return false
    }

    private suspend fun runSetupScript(): Boolean {
        // Steps 4-6 are handled by a single script. Only run once.
        if (setupScriptRan) return true

        val deployed = scriptManager.deployScripts()
        if (!deployed) {
            _currentOutput.value = "Failed to deploy scripts to Termux"
            updateCurrentStepStatus(StepStatus.Failed("Script deployment failed"))
            return false
        }

        _currentOutput.value = "Running setup (this may take several minutes)..."

        val result = commandRunner.execute(
            commandPath = scriptManager.getScriptPath("setup-environment.sh"),
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_SETUP_MS
        )

        if (result.isSuccess) {
            setupScriptRan = true
            _currentOutput.value = result.stdout
            // Mark all three sub-steps as completed in one emission
            _steps.update { currentSteps ->
                currentSteps.toMutableList().also { list ->
                    listOf(SetupStep.InstallProot, SetupStep.InstallJava, SetupStep.DownloadRuneLite).forEach { step ->
                        val index = list.indexOfFirst { it.step == step }
                        if (index >= 0) {
                            list[index] = list[index].copy(status = StepStatus.Completed)
                        }
                    }
                }
            }
            return true
        } else {
            val errorOutput = result.stderr ?: result.error ?: "Unknown error"
            _currentOutput.value = errorOutput
            updateCurrentStepStatus(StepStatus.Failed(errorOutput))
            return false
        }
    }

    private suspend fun runVerification(): Boolean {
        _currentOutput.value = "Verifying setup..."

        val checks = listOf(
            "proot-distro list 2>/dev/null | grep -q ubuntu && echo 'PASS: proot' || echo 'FAIL: proot'",
            "proot-distro login ubuntu -- which java && echo 'PASS: java' || echo 'FAIL: java'",
            "proot-distro login ubuntu -- test -f /root/runelite/RuneLite.jar && echo 'PASS: runelite' || echo 'FAIL: runelite'",
            "which termux-x11 && echo 'PASS: x11' || echo 'FAIL: x11'"
        )

        val verifyScript = checks.joinToString("\n")

        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", verifyScript),
            background = true,
            timeoutMs = 60L * 1000  // 60 seconds for verification (proot commands are slow)
        )

        val output = result.stdout ?: ""
        _currentOutput.value = output

        if (output.contains("FAIL")) {
            val failures = output.lines().filter { it.startsWith("FAIL") }
            updateCurrentStepStatus(
                StepStatus.Failed("Verification failed: ${failures.joinToString(", ")}")
            )
            return false
        }

        return result.isSuccess
    }

    private fun evaluateCompletedSteps() {
        _steps.update { steps ->
            steps.map { stepState ->
                when (stepState.step) {
                    SetupStep.InstallTermux -> {
                        if (termuxHelper.isTermuxInstalled()) {
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    SetupStep.InstallTermuxX11 -> {
                        if (termuxHelper.isTermuxX11Installed()) {
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    else -> stepState
                }
            }
        }
    }

    private fun updateStepStatus(index: Int, status: StepStatus) {
        _steps.update { steps ->
            steps.toMutableList().also { it[index] = it[index].copy(status = status) }
        }
    }

    private fun updateCurrentStepStatus(status: StepStatus) {
        val current = _currentStep.value ?: return
        val index = _steps.value.indexOfFirst { it.step == current }
        if (index >= 0) updateStepStatus(index, status)
    }
}

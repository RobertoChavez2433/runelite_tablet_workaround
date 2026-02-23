package com.runelitetablet.setup

import android.content.Context
import android.content.Intent
import com.runelitetablet.cleanup.CleanupManager
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.installer.GitHubRepo
import com.runelitetablet.installer.InstallResult
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    fun requestTermuxPermission()
    fun launchIntent(intent: Intent)
}

class SetupOrchestrator(
    private val context: Context,
    private val termuxHelper: TermuxPackageHelper,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val commandRunner: TermuxCommandRunner,
    private val scriptManager: ScriptManager,
    private val cleanupManager: CleanupManager
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

    init {
        ApkInstaller.onNeedsUserAction = { intent ->
            actions?.launchIntent(intent)
        }
    }

    suspend fun runSetup() {
        AppLog.cleanup("runSetup: starting — delegating to CleanupManager")
        cleanupManager.cleanup()
        AppLog.step("setup", "runSetup: cleanup complete, evaluating pre-checks")
        evaluateCompletedSteps()
        AppLog.step("setup", "runSetup: pre-checks done, starting from index 0")
        runSetupFrom(0)
    }

    suspend fun retryCurrentStep() {
        if (failedStepIndex < 0) return
        val index = failedStepIndex
        val stepState = _steps.value[index]
        AppLog.step(stepState.step.id, "retryCurrentStep: index=$index stepId=${stepState.step.id}")

        val oldStatus = _currentStep.value
        _currentStep.value = stepState.step
        AppLog.state("retryCurrentStep: currentStep ${oldStatus?.id} -> ${stepState.step.id}")

        updateStepStatus(index, StepStatus.InProgress)
        val oldOutput = _currentOutput.value
        _currentOutput.value = null
        AppLog.state("retryCurrentStep: currentOutput '$oldOutput' -> null")
        failedStepIndex = -1

        try {
            val startMs = System.currentTimeMillis()
            val success = executeStep(stepState.step)
            val durationMs = System.currentTimeMillis() - startMs
            AppLog.step(stepState.step.id, "retryCurrentStep: executeStep done success=$success durationMs=$durationMs")
            if (success) {
                updateStepStatus(index, StepStatus.Completed)
                runSetupFrom(index + 1)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.e("STEP", "retryCurrentStep: exception in step=${stepState.step.id} message=${e.message}", e)
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
        AppLog.step("setup", "runSetupFrom: loop entry startIndex=$startIndex totalSteps=${_steps.value.size}")
        for (index in startIndex until _steps.value.size) {
            val stepState = _steps.value[index]
            if (stepState.status is StepStatus.Completed) {
                AppLog.step(stepState.step.id, "runSetupFrom: skipping index=$index stepId=${stepState.step.id} reason=already_completed")
                continue
            }

            AppLog.step(stepState.step.id, "runSetupFrom: dispatching index=$index stepId=${stepState.step.id}")
            val oldCurrentStep = _currentStep.value
            _currentStep.value = stepState.step
            AppLog.state("runSetupFrom: currentStep ${oldCurrentStep?.id} -> ${stepState.step.id}")

            updateStepStatus(index, StepStatus.InProgress)
            val oldOutput = _currentOutput.value
            _currentOutput.value = null
            AppLog.state("runSetupFrom: currentOutput '$oldOutput' -> null")

            try {
                val startMs = System.currentTimeMillis()
                val success = executeStep(stepState.step)
                val durationMs = System.currentTimeMillis() - startMs
                AppLog.step(stepState.step.id, "runSetupFrom: step complete success=$success durationMs=$durationMs index=$index")
                if (success) {
                    updateStepStatus(index, StepStatus.Completed)
                } else {
                    AppLog.step(stepState.step.id, "runSetupFrom: step returned false, halting at index=$index")
                    failedStepIndex = index
                    return
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.e("STEP", "runSetupFrom: exception at index=$index stepId=${stepState.step.id}: ${e.message}", e)
                updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
                _currentOutput.value = e.message
                failedStepIndex = index
                return
            }
        }
        AppLog.step("setup", "runSetupFrom: loop complete, all steps done")
        val oldCurrentStep = _currentStep.value
        _currentStep.value = null
        AppLog.state("runSetupFrom: currentStep ${oldCurrentStep?.id} -> null")
        val oldOutput = _currentOutput.value
        _currentOutput.value = null
        AppLog.state("runSetupFrom: currentOutput '$oldOutput' -> null (cleanup)")
    }

    private suspend fun executeStep(step: SetupStep): Boolean {
        AppLog.step(step.id, "executeStep: dispatching stepId=${step.id}")
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
        var lastEmittedPercent = -1
        val apkFile = apkDownloader.download(repo) { bytesRead, totalBytes ->
            if (totalBytes > 0) {
                val percent = (bytesRead * 100 / totalBytes).toInt()
                if (percent != lastEmittedPercent) {
                    lastEmittedPercent = percent
                    _currentOutput.value = "Downloading ${repo.name}... $percent%"
                }
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
                // Defensive fallback — normally the receiver launches the confirm intent
                // directly and the deferred stays alive for the final result. This branch
                // is only reached if the confirm intent was null.
                _currentOutput.value = "Install requires confirmation — please retry"
                updateCurrentStepStatus(StepStatus.Failed("Install requires confirmation — please retry"))
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
        // Request the Termux RUN_COMMAND runtime permission before checking
        actions?.requestTermuxPermission()

        val alreadyWorking = verifyPermissions()
        if (alreadyWorking) return true

        val instructions = listOf(
            "Open Termux and run:\necho \"allow-external-apps=true\" >> ~/.termux/termux.properties",
            "Grant the \"Run commands in Termux\" permission when prompted (or via Settings > Apps > RuneLite for Tablet > Permissions)",
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

        // proot-distro commands return non-zero exit codes due to harmless
        // /proc/self/fd binding warnings in background (no-PTY) mode. Check for
        // the script's success marker in stdout instead of relying on exit code.
        val scriptCompleted = result.stdout?.contains("=== Setup complete ===") == true

        if (result.isSuccess || scriptCompleted) {
            // Truncate to last 2000 chars — setup scripts can produce MB-scale output
            _currentOutput.value = result.stdout?.let { if (it.length > 2000) it.takeLast(2000) else it }
            if (!result.isSuccess) {
                AppLog.w("STEP", "runSetupScript: non-zero exit (${result.exitCode}) but script completed — proot fd warnings likely")
            }
            // Mark all three sub-steps as completed in one emission
            val oldSteps = _steps.value
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
            AppLog.state(
                "runSetupScript: batch-completed proot/java/runelite steps; " +
                    "steps changed from ${oldSteps.map { "${it.step.id}=${it.status::class.simpleName}" }} " +
                    "to ${_steps.value.map { "${it.step.id}=${it.status::class.simpleName}" }}"
            )
            return true
        } else {
            val errorOutput = result.stderr ?: result.error ?: "Unknown error"
            AppLog.e("STEP", "runSetupScript: script failed exitCode=${result.exitCode} errorOutput=$errorOutput stdoutTail=${result.stdout?.takeLast(200)}")
            _currentOutput.value = errorOutput
            updateCurrentStepStatus(StepStatus.Failed(errorOutput))
            return false
        }
    }

    private suspend fun runVerification(): Boolean {
        _currentOutput.value = "Verifying setup..."

        val checks = listOf(
            // Check rootfs directory directly — proot-distro list may not show manually-extracted rootfs
            "[ -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ] && echo 'PASS: proot' || echo 'FAIL: proot'",
            "proot-distro login ubuntu -- which java < /dev/null && echo 'PASS: java' || echo 'FAIL: java'",
            "proot-distro login ubuntu -- test -f /root/runelite/RuneLite.jar < /dev/null && echo 'PASS: runelite' || echo 'FAIL: runelite'",
            // Use command -v (POSIX) and check common x11 paths for background mode PATH issues
            // Braces group the OR check so && applies to the whole condition (not just the second operand)
            "{ command -v termux-x11 >/dev/null 2>&1 || [ -f \"\$PREFIX/bin/termux-x11\" ]; } && echo 'PASS: x11' || echo 'FAIL: x11'"
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

        // All PASS markers present — ignore exit code. proot commands return non-zero
        // even on success due to /proc/self/fd binding warnings in background (no-PTY) mode.
        if (result.exitCode != 0) {
            AppLog.w("STEP", "runVerification: non-zero exit (${result.exitCode}) but all checks passed — proot fd warnings likely")
        }
        return true
    }

    private suspend fun evaluateCompletedSteps() {
        AppLog.step("setup", "evaluateCompletedSteps: checking pre-installed packages")
        val termuxInstalled = withContext(Dispatchers.IO) { termuxHelper.isTermuxInstalled() }
        val termuxX11Installed = withContext(Dispatchers.IO) { termuxHelper.isTermuxX11Installed() }
        AppLog.step("termux", "evaluateCompletedSteps: isTermuxInstalled=$termuxInstalled")
        AppLog.step("termux_x11", "evaluateCompletedSteps: isTermuxX11Installed=$termuxX11Installed")

        _steps.update { steps ->
            steps.map { stepState ->
                when (stepState.step) {
                    SetupStep.InstallTermux -> {
                        if (termuxInstalled) {
                            AppLog.step("termux", "evaluateCompletedSteps: marking termux as Completed (pre-check passed)")
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    SetupStep.InstallTermuxX11 -> {
                        if (termuxX11Installed) {
                            AppLog.step("termux_x11", "evaluateCompletedSteps: marking termux_x11 as Completed (pre-check passed)")
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    else -> stepState
                }
            }
        }
    }

    private fun updateStepStatus(index: Int, status: StepStatus) {
        val oldStatus = _steps.value.getOrNull(index)?.status
        val stepId = _steps.value.getOrNull(index)?.step?.id ?: "unknown"
        AppLog.step(stepId, "updateStepStatus: index=$index stepId=$stepId ${oldStatus?.let { it::class.simpleName } ?: "?"} -> ${status::class.simpleName}")
        _steps.update { steps ->
            steps.toMutableList().also { it[index] = it[index].copy(status = status) }
        }
        AppLog.state("updateStepStatus: steps[index=$index] stepId=$stepId -> ${status::class.simpleName}")
    }

    private fun updateCurrentStepStatus(status: StepStatus) {
        val current = _currentStep.value ?: return
        val index = _steps.value.indexOfFirst { it.step == current }
        if (index >= 0) updateStepStatus(index, status)
    }
}

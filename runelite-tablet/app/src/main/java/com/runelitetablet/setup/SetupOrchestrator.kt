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
import kotlinx.coroutines.TimeoutCancellationException
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
    private val cleanupManager: CleanupManager,
    private val stateStore: SetupStateStore
) {
    @Volatile var actions: SetupActions? = null
        set(value) {
            field = value
            ApkInstaller.onNeedsUserAction = if (value != null) {
                { intent -> value.launchIntent(intent) }
            } else null
        }

    private val _steps = MutableStateFlow(
        SetupStep.allSteps.map { StepState(it) }
    )
    val steps: StateFlow<List<StepState>> = _steps.asStateFlow()

    private val _currentStep = MutableStateFlow<SetupStep?>(null)
    val currentStep: StateFlow<SetupStep?> = _currentStep.asStateFlow()

    private val _currentOutput = MutableStateFlow<String?>(null)
    val currentOutput: StateFlow<String?> = _currentOutput.asStateFlow()

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Reconciling)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    @Volatile private var failedStepIndex: Int = -1

    companion object {
        /** Timeout for check-markers.sh reconciliation on startup */
        private const val MARKER_CHECK_TIMEOUT_MS = 10_000L

        /** Map of setup step to its marker key and script name */
        private val MODULAR_STEPS = mapOf(
            SetupStep.InstallProot to Pair("step-proot", "install-proot.sh"),
            SetupStep.InstallJava to Pair("step-java", "install-java.sh"),
            SetupStep.DownloadRuneLite to Pair("step-runelite", "download-runelite.sh")
        )
    }

    suspend fun runSetup() {
        _setupState.value = SetupState.Reconciling
        AppLog.cleanup("runSetup: starting — delegating to CleanupManager")
        cleanupManager.cleanup()

        // Version check: if stored version is stale, clear all cached state and re-run from scratch
        if (!stateStore.isVersionCurrent()) {
            AppLog.step("setup", "runSetup: version mismatch (stored=${stateStore.getStoredVersion()} current=${SetupStateStore.CURRENT_SCRIPT_VERSION}) — clearing all state")
            stateStore.clearAll()
        }

        AppLog.step("setup", "runSetup: evaluating completed steps (suspend)")
        evaluateCompletedSteps()

        AppLog.step("setup", "runSetup: reconciling against markers")
        reconcileWithMarkers()

        _setupState.value = SetupState.SetupInProgress
        AppLog.step("setup", "runSetup: reconciliation done, starting from index 0")
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
        _setupState.value = SetupState.SetupComplete
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
            SetupStep.InstallProot -> executeModularScript("install-proot.sh", "step-proot")
            SetupStep.InstallJava -> executeModularScript("install-java.sh", "step-java")
            SetupStep.DownloadRuneLite -> executeModularScript("download-runelite.sh", "step-runelite")
            SetupStep.VerifySetup -> runVerification()
        }
    }

    /**
     * Execute a modular setup shell script. Deploys scripts first if needed,
     * then runs the specified script and marks the step complete in the state store.
     */
    private suspend fun executeModularScript(scriptName: String, markerKey: String): Boolean {
        val deployed = scriptManager.deployScripts()
        if (!deployed) {
            _currentOutput.value = "Failed to deploy scripts to Termux"
            updateCurrentStepStatus(StepStatus.Failed("Script deployment failed"))
            return false
        }

        val configsDeployed = scriptManager.deployConfigs()
        if (!configsDeployed) {
            _currentOutput.value = "Failed to deploy configs to Termux"
            updateCurrentStepStatus(StepStatus.Failed("Config deployment failed"))
            return false
        }

        _currentOutput.value = "Running $scriptName (this may take several minutes)..."

        val result = commandRunner.execute(
            commandPath = scriptManager.getScriptPath(scriptName),
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_SETUP_MS
        )

        // Check for the script's completion marker in stdout.
        // proot-distro commands return non-zero exit codes due to harmless
        // /proc/self/fd binding warnings in background (no-PTY) mode.
        val completionMarker = "=== ${scriptName.removeSuffix(".sh")} complete ==="
        val scriptCompleted = result.stdout?.contains(completionMarker) == true

        if (result.isSuccess || scriptCompleted) {
            // Truncate to last 2000 chars — setup scripts can produce MB-scale output
            _currentOutput.value = result.stdout?.let { if (it.length > 2000) it.takeLast(2000) else it }
            if (!result.isSuccess) {
                AppLog.w("STEP", "executeModularScript: non-zero exit (${result.exitCode}) for $scriptName but script completed — proot fd warnings likely")
            }
            stateStore.markCompleted(markerKey)
            stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
            AppLog.step(markerKey, "executeModularScript: $scriptName completed, marker '$markerKey' written to state store")
            return true
        } else {
            val errorOutput = result.stderr ?: result.error ?: "Unknown error"
            AppLog.e("STEP", "executeModularScript: $scriptName failed exitCode=${result.exitCode} errorOutput=$errorOutput stdoutTail=${result.stdout?.takeLast(200)}")
            _currentOutput.value = errorOutput
            updateCurrentStepStatus(StepStatus.Failed(errorOutput))
            return false
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

        val result = apkInstaller.install(apkFile, repo.expectedPackageName)
        return when (result) {
            is InstallResult.Success -> {
                // Delete downloaded APK after successful installation to reclaim disk space
                try {
                    if (apkFile.exists()) {
                        val sizeBytes = apkFile.length()
                        apkFile.delete()
                        AppLog.install("installPackage: deleted APK after install path=${apkFile.name} size=$sizeBytes")
                    }
                } catch (e: Exception) {
                    AppLog.w("INSTALL", "installPackage: failed to delete APK after install: ${e.message}")
                }
                true
            }
            is InstallResult.NeedsUserAction -> {
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

    private suspend fun runVerification(): Boolean {
        _currentOutput.value = "Verifying setup..."

        val checks = listOf(
            "[ -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ] && echo 'PASS: proot' || echo 'FAIL: proot'",
            "proot-distro login ubuntu -- which java < /dev/null && echo 'PASS: java' || echo 'FAIL: java'",
            "proot-distro login ubuntu -- test -f /root/runelite/RuneLite.jar < /dev/null && echo 'PASS: runelite' || echo 'FAIL: runelite'",
            "{ command -v termux-x11 >/dev/null 2>&1 || [ -f \"\$PREFIX/bin/termux-x11\" ]; } && echo 'PASS: x11' || echo 'FAIL: x11'"
        )

        val verifyScript = checks.joinToString("\n")

        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", verifyScript),
            background = true,
            timeoutMs = 60L * 1000
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

        if (result.exitCode != 0) {
            AppLog.w("STEP", "runVerification: non-zero exit (${result.exitCode}) but all checks passed — proot fd warnings likely")
        }
        return true
    }

    /**
     * Evaluate pre-completed steps: check package installations and load cached state from SharedPreferences.
     */
    private suspend fun evaluateCompletedSteps() {
        AppLog.step("setup", "evaluateCompletedSteps: checking pre-installed packages and cached state")
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
                    SetupStep.InstallProot -> {
                        if (stateStore.isCompleted("step-proot")) {
                            AppLog.step("proot", "evaluateCompletedSteps: marking proot as Completed (cached state)")
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    SetupStep.InstallJava -> {
                        if (stateStore.isCompleted("step-java")) {
                            AppLog.step("java", "evaluateCompletedSteps: marking java as Completed (cached state)")
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    SetupStep.DownloadRuneLite -> {
                        if (stateStore.isCompleted("step-runelite")) {
                            AppLog.step("runelite", "evaluateCompletedSteps: marking runelite as Completed (cached state)")
                            stepState.copy(status = StepStatus.Completed)
                        } else stepState
                    }
                    else -> stepState
                }
            }
        }
    }

    /**
     * Reconcile SharedPreferences cache against actual marker files in Termux.
     * Runs check-markers.sh with a 10-second timeout. On failure (Termux not running, timeout),
     * keeps cached state as-is and logs a warning.
     */
    private suspend fun reconcileWithMarkers() {
        // Deploy scripts first so check-markers.sh is available
        val deployed = scriptManager.deployScripts()
        if (!deployed) {
            AppLog.w("STEP", "reconcileWithMarkers: script deployment failed, keeping cached state")
            return
        }

        try {
            val result = commandRunner.execute(
                commandPath = scriptManager.getScriptPath("check-markers.sh"),
                background = true,
                timeoutMs = MARKER_CHECK_TIMEOUT_MS
            )

            if (!result.isSuccess) {
                AppLog.w("STEP", "reconcileWithMarkers: check-markers.sh returned non-zero (${result.exitCode}), keeping cached state")
                return
            }

            val output = result.stdout ?: ""
            AppLog.step("setup", "reconcileWithMarkers: output=$output")

            // Parse marker output
            val lines = output.lines()

            // Check version mismatch from markers
            val versionLine = lines.find { it.startsWith("VERSION ") }
            val markerVersion = versionLine?.removePrefix("VERSION ")?.trim() ?: "none"
            if (markerVersion != "none" && markerVersion != SetupStateStore.CURRENT_SCRIPT_VERSION) {
                AppLog.step("setup", "reconcileWithMarkers: marker version mismatch (marker=$markerVersion expected=${SetupStateStore.CURRENT_SCRIPT_VERSION}) — clearing all state")
                stateStore.clearAll()
                // Reset all modular steps to Pending
                _steps.update { steps ->
                    steps.map { stepState ->
                        if (MODULAR_STEPS.containsKey(stepState.step)) {
                            stepState.copy(status = StepStatus.Pending)
                        } else stepState
                    }
                }
                return
            }

            // Reconcile each step
            for (line in lines) {
                when {
                    line.startsWith("PRESENT ") -> {
                        val key = line.removePrefix("PRESENT ").trim()
                        val step = MODULAR_STEPS.entries.find { it.value.first == key }?.key
                        if (step != null) {
                            stateStore.markCompleted(key)
                            val index = _steps.value.indexOfFirst { it.step == step }
                            if (index >= 0) {
                                updateStepStatus(index, StepStatus.Completed)
                                AppLog.step(key, "reconcileWithMarkers: PRESENT — upgraded to Completed")
                            }
                        }
                    }
                    line.startsWith("ABSENT ") -> {
                        val key = line.removePrefix("ABSENT ").trim()
                        val step = MODULAR_STEPS.entries.find { it.value.first == key }?.key
                        if (step != null) {
                            // Downgrade: marker missing means step hasn't actually completed
                            val index = _steps.value.indexOfFirst { it.step == step }
                            if (index >= 0 && _steps.value[index].status is StepStatus.Completed) {
                                updateStepStatus(index, StepStatus.Pending)
                                AppLog.step(key, "reconcileWithMarkers: ABSENT — downgraded to Pending")
                            }
                        }
                    }
                }
            }

            stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
        } catch (e: TimeoutCancellationException) {
            AppLog.w("STEP", "reconcileWithMarkers: timeout after ${MARKER_CHECK_TIMEOUT_MS}ms, keeping cached state")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("STEP", "reconcileWithMarkers: exception (${e.message}), keeping cached state")
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

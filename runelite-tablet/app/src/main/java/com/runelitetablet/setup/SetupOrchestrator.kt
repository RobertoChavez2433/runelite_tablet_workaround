package com.runelitetablet.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
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
    fun requestNotificationPermission()
    fun requestBatteryOptimization(packageName: String)
    fun openAppSettings(packageName: String)
    fun launchIntent(intent: Intent)
    /** Launch an Activity for result using the registered ActivityResultLauncher. */
    fun launchAuthActivity(intent: Intent)
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

    private val _permissionPhase = MutableStateFlow<PermissionPhase>(PermissionPhase.TermuxConfig)
    val permissionPhase: StateFlow<PermissionPhase> = _permissionPhase.asStateFlow()

    /** Set to true when the permissions step is actively waiting for user action */
    private val _awaitingPermissionCompletion = MutableStateFlow(false)
    val awaitingPermissionCompletion: StateFlow<Boolean> = _awaitingPermissionCompletion.asStateFlow()

    // Only accessed from viewModelScope (Main dispatcher) — no @Volatile needed
    private var failedStepIndex: Int = -1

    /** Reset all transient state for a fresh setup run (called by resetSetup/runSetupForHealth). */
    fun resetState() {
        _steps.value = SetupStep.allSteps.map { StepState(it) }
        _currentStep.value = null
        _currentOutput.value = null
        _setupState.value = SetupState.Reconciling
        _permissionPhase.value = PermissionPhase.TermuxConfig
        _awaitingPermissionCompletion.value = false
        failedStepIndex = -1
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"

        /** Timeout for check-markers.sh reconciliation on startup */
        private const val MARKER_CHECK_TIMEOUT_MS = 10_000L

        /** Map of setup step to its marker key and script name */
        private val MODULAR_STEPS = mapOf(
            SetupStep.InstallProot to Pair("step-proot", "install-proot.sh"),
            SetupStep.InstallJava to Pair("step-java", "install-java.sh"),
            SetupStep.DownloadRuneLite to Pair("step-runelite", "download-runelite.sh"),
            SetupStep.InstallGpuDrivers to Pair("step-gpu", "setup-gpu.sh")
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
        return try {
            val result = commandRunner.execute(
                commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/echo",
                arguments = arrayOf("ok"),
                background = true,
                timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
            )
            result.isSuccess && result.stdout?.trim() == "ok"
        } catch (e: SecurityException) {
            AppLog.w("permissions", "verifyPermissions: SecurityException (permission not granted yet)")
            false
        }
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
            SetupStep.InstallGpuDrivers -> executeGpuStep()
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

    /**
     * Phased permission flow. Checks each permission phase in order:
     * 1. TermuxConfig — verify that allow-external-apps is set (test RUN_COMMAND)
     * 2. RuntimePermission — request com.termux.permission.RUN_COMMAND
     * 3. BatteryOptimization — request battery optimization exemption
     *
     * If all phases pass immediately, returns true. Otherwise, sets the
     * permission phase state and halts — the ViewModel will drive the UI and
     * call [advancePermissionPhase] when user completes each phase.
     */
    private suspend fun handlePermissionsStep(): Boolean {
        // Check if everything already works (fast path for re-runs)
        val alreadyWorking = verifyPermissions()
        if (alreadyWorking && hasRequiredBatteryExemptions()) {
            AppLog.step("permissions", "handlePermissionsStep: all permissions already configured")
            _permissionPhase.value = PermissionPhase.Complete
            return true
        }

        // Determine which phase to start at
        if (alreadyWorking) {
            // Termux config + runtime permission already working, jump to battery
            AppLog.step("permissions", "handlePermissionsStep: RUN_COMMAND works, checking battery")
            if (hasRequiredBatteryExemptions()) {
                _permissionPhase.value = PermissionPhase.Complete
                return true
            }
            _permissionPhase.value = PermissionPhase.BatteryOptimization
        } else {
            // Start from the beginning
            _permissionPhase.value = PermissionPhase.TermuxConfig
        }

        // Halt the setup loop — the ViewModel will drive phase progression
        _awaitingPermissionCompletion.value = true
        val stepIndex = _steps.value.indexOfFirst { it.step == SetupStep.EnablePermissions }
        updateStepStatus(stepIndex, StepStatus.InProgress)
        _currentOutput.value = null
        failedStepIndex = stepIndex
        return false
    }

    /**
     * Called by the ViewModel when the user completes a permission phase.
     * Advances to the next phase or completes the step.
     * Returns true if all permission phases are now complete.
     */
    suspend fun advancePermissionPhase(): Boolean {
        val currentPhase = _permissionPhase.value
        AppLog.step("permissions", "advancePermissionPhase: currentPhase=${currentPhase::class.simpleName}")

        return when (currentPhase) {
            is PermissionPhase.TermuxConfig -> {
                // We can't verify Termux config independently — verifyPermissions() sends
                // a RUN_COMMAND intent which requires the Android runtime permission (Phase 2).
                // Without it, we always get SecurityException regardless of Termux config state.
                // Advance to Phase 2 unconditionally and request the runtime permission.
                // If BOTH Termux config and runtime permission are already set, verifyPermissions()
                // will succeed and we'll skip through Phase 2 as well.
                AppLog.step("permissions", "advancePermissionPhase: TermuxConfig phase done (user returned), moving to RuntimePermission")
                _permissionPhase.value = PermissionPhase.RuntimePermission
                // Auto-advance: request the runtime permission immediately
                actions?.requestTermuxPermission()
                // If permission was already granted, the system dialog won't appear —
                // verify and skip RuntimePermission phase, advance to BatteryOptimization.
                if (verifyPermissions()) {
                    AppLog.step("permissions", "advancePermissionPhase: RuntimePermission already granted, advancing to BatteryOptimization")
                    _permissionPhase.value = PermissionPhase.BatteryOptimization
                    requestBatteryOptimization()
                }
                true
            }
            is PermissionPhase.RuntimePermission -> {
                // Verify RUN_COMMAND permission is now granted
                val working = verifyPermissions()
                if (working) {
                    AppLog.step("permissions", "advancePermissionPhase: RuntimePermission verified, moving to BatteryOptimization")
                    _permissionPhase.value = PermissionPhase.BatteryOptimization
                    true
                } else {
                    AppLog.step("permissions", "advancePermissionPhase: RuntimePermission not yet granted")
                    false
                }
            }
            is PermissionPhase.BatteryOptimization -> {
                val pendingPackage = getNextBatteryOptimizationPackage()
                if (pendingPackage == null) {
                    AppLog.step("permissions", "advancePermissionPhase: BatteryOptimization verified, all phases complete")
                    _permissionPhase.value = PermissionPhase.Complete
                    completePermissionsStep()
                    true
                } else {
                    AppLog.step("permissions", "advancePermissionPhase: battery exemption still missing for $pendingPackage")
                    false
                }
            }
            is PermissionPhase.Complete -> {
                AppLog.step("permissions", "advancePermissionPhase: already complete")
                true
            }
        }
    }

    /**
     * Request battery optimization exemption for the given package.
     * Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with Samsung fallback.
     */
    fun requestBatteryOptimization() {
        val packageName = getNextBatteryOptimizationPackage() ?: TERMUX_PACKAGE
        try {
            if (packageName == TERMUX_X11_PACKAGE) {
                // Samsung often returns immediately for auxiliary packages here without
                // exposing a useful exemption UI. Jump straight to app settings instead.
                actions?.openAppSettings(packageName)
            } else {
                actions?.requestBatteryOptimization(packageName)
            }
        } catch (e: Exception) {
            AppLog.w("PERM", "requestBatteryOptimization: intent failed for $packageName, falling back to app settings: ${e.message}")
            actions?.openAppSettings(packageName)
        }
    }

    /**
     * Also request battery optimization for our own app if not already exempt.
     */
    fun requestOwnBatteryOptimization() {
        if (!isBatteryOptimized(context.packageName)) {
            try {
                actions?.requestBatteryOptimization(context.packageName)
            } catch (e: Exception) {
                AppLog.w("PERM", "requestOwnBatteryOptimization: failed: ${e.message}")
            }
        }
    }

    /**
     * Check if notification permission is granted. On Android 12 and below,
     * this always returns true (no runtime permission needed).
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Check if a package is exempt from battery optimization.
     */
    fun isBatteryOptimized(packageName: String): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasRequiredBatteryExemptions(): Boolean =
        getNextBatteryOptimizationPackage() == null

    private fun getNextBatteryOptimizationPackage(): String? {
        return when {
            !isBatteryOptimized(TERMUX_PACKAGE) -> TERMUX_PACKAGE
            !isBatteryOptimized(TERMUX_X11_PACKAGE) -> TERMUX_X11_PACKAGE
            else -> null
        }
    }

    /**
     * Mark the permissions step as completed and resume the setup loop.
     */
    private suspend fun completePermissionsStep() {
        _awaitingPermissionCompletion.value = false
        val stepIndex = _steps.value.indexOfFirst { it.step == SetupStep.EnablePermissions }
        if (stepIndex >= 0) {
            updateStepStatus(stepIndex, StepStatus.Completed)
            AppLog.step("permissions", "completePermissionsStep: permissions step completed, resuming setup")
            runSetupFrom(stepIndex + 1)
        }
    }

    /**
     * GPU driver installation step. Non-blocking: if it fails, setup continues
     * and RuneLite falls back to software rendering at launch time.
     */
    private suspend fun executeGpuStep(): Boolean {
        // Check if already done
        if (stateStore.isCompleted("step-gpu")) {
            AppLog.step("gpu", "executeGpuStep: already completed (cached)")
            return true
        }

        val deployed = scriptManager.deployScripts()
        if (!deployed) {
            AppLog.w("STEP", "executeGpuStep: script deployment failed, skipping GPU setup")
            // Non-blocking: return true to continue setup
            return true
        }

        _currentOutput.value = "Installing GPU drivers (this may take several minutes)..."

        val result = commandRunner.execute(
            commandPath = scriptManager.getScriptPath("setup-gpu.sh"),
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_SETUP_MS
        )

        val completionMarker = "GPU_SETUP_COMPLETE"
        val scriptCompleted = result.stdout?.contains(completionMarker) == true

        if (result.isSuccess || scriptCompleted) {
            _currentOutput.value = result.stdout?.let { if (it.length > 2000) it.takeLast(2000) else it }
            stateStore.markCompleted("step-gpu")
            AppLog.step("gpu", "executeGpuStep: GPU drivers installed successfully")
            return true
        } else {
            // GPU setup failed — non-blocking, continue with software rendering
            val errorOutput = result.stderr ?: result.error ?: "Unknown error"
            AppLog.w("STEP", "executeGpuStep: GPU setup failed (non-blocking): $errorOutput")
            _currentOutput.value = "GPU driver installation failed (software rendering will be used). Error: $errorOutput"
            // Mark as completed anyway — we don't want to block setup
            stateStore.markCompleted("step-gpu")
            return true
        }
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
        val (termuxInstalled, termuxX11Installed) = withContext(Dispatchers.IO) {
            Pair(termuxHelper.isTermuxInstalled(), termuxHelper.isTermuxX11Installed())
        }
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
                    SetupStep.InstallGpuDrivers -> {
                        if (stateStore.isCompleted("step-gpu")) {
                            AppLog.step("gpu", "evaluateCompletedSteps: marking gpu as Completed (cached state)")
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
                                stateStore.clearCompleted(key)
                                AppLog.step(key, "reconcileWithMarkers: ABSENT — downgraded to Pending and cleared stateStore")
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

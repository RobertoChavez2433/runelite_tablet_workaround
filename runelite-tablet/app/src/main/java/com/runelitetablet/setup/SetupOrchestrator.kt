package com.runelitetablet.setup

import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.Cleaner
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.installer.GitHubRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SetupOrchestrator(
    private val packageChecker: PackageChecker,
    private val stepRunner: SetupStepRunner,
    private val verifier: SetupVerifier,
    private val reconciler: MarkerReconciler,
    private val cleaner: Cleaner,
    private val stateStore: SetupStateStore,
    private val permissionChecker: com.runelitetablet.domain.setup.PermissionChecker,
    private val apkInstaller: ApkInstaller,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger? = null
) {
    @Volatile var actions: SetupActions? = null
        set(value) {
            field = value
            apkInstaller.onNeedsUserAction = if (value != null) { { intent -> value.launchIntent(intent) } } else null
        }

    private val _steps = MutableStateFlow(SetupStep.allSteps.map { StepState(it) })
    val steps: StateFlow<List<StepState>> = _steps.asStateFlow()
    private val _currentStep = MutableStateFlow<SetupStep?>(null)
    private val _currentOutput = MutableStateFlow<String?>(null)
    val currentOutput: StateFlow<String?> = _currentOutput.asStateFlow()
    private val _permissionPhase = MutableStateFlow<PermissionPhase>(PermissionPhase.TermuxConfig)
    val permissionPhase: StateFlow<PermissionPhase> = _permissionPhase.asStateFlow()
    private val _awaitingPermissionCompletion = MutableStateFlow(false)
    val awaitingPermissionCompletion: StateFlow<Boolean> = _awaitingPermissionCompletion.asStateFlow()
    private var failedStepIndex: Int = -1

    fun resetState() {
        _steps.value = SetupStep.allSteps.map { StepState(it) }
        _currentStep.value = null; _currentOutput.value = null
        _permissionPhase.value = PermissionPhase.TermuxConfig
        _awaitingPermissionCompletion.value = false; failedStepIndex = -1
    }

    suspend fun runSetup() {
        cleaner.cleanup()
        if (!stateStore.isVersionCurrent()) stateStore.clearAll()
        evaluateCompletedSteps()
        reconcileWithMarkers()
        runSetupFrom(0)
    }

    suspend fun retryCurrentStep() {
        if (failedStepIndex < 0) return
        val index = failedStepIndex
        _currentStep.value = _steps.value[index].step
        updateStepStatus(index, StepStatus.InProgress)
        _currentOutput.value = null; failedStepIndex = -1
        try {
            if (executeStep(_steps.value[index].step)) { updateStepStatus(index, StepStatus.Completed); runSetupFrom(index + 1) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
            _currentOutput.value = e.message; failedStepIndex = index
        }
    }

    private suspend fun runSetupFrom(startIndex: Int) {
        for (index in startIndex until _steps.value.size) {
            val stepState = _steps.value[index]
            if (stepState.status is StepStatus.Completed) continue
            _currentStep.value = stepState.step
            updateStepStatus(index, StepStatus.InProgress); _currentOutput.value = null
            try {
                if (executeStep(stepState.step)) updateStepStatus(index, StepStatus.Completed)
                else { failedStepIndex = index; return }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
                _currentOutput.value = e.message; failedStepIndex = index; return
            }
        }
        _currentStep.value = null; _currentOutput.value = null
    }

    private val stepCallbacks = object : SetupStepRunner.Callbacks {
        override fun updateOutput(message: String?) { _currentOutput.value = message }
        override fun requestInstallPermission() { actions?.requestInstallPermission() }
        override fun launchIntent(intent: android.content.Intent) { actions?.launchIntent(intent) }
    }

    private suspend fun executeStep(step: SetupStep): Boolean = when (step) {
        SetupStep.InstallTermux -> stepRunner.installPackage(GitHubRepo.TERMUX, { packageChecker.isTermuxInstalled() }, stepCallbacks)
        SetupStep.InstallTermuxX11 -> stepRunner.installPackage(GitHubRepo.TERMUX_X11, { packageChecker.isTermuxX11Installed() }, stepCallbacks)
        SetupStep.EnablePermissions -> handlePermissionsStep()
        SetupStep.InstallProot -> stepRunner.executeModularScript("install-proot.sh", "step-proot", stepCallbacks)
        SetupStep.InstallJava -> stepRunner.executeModularScript("install-java.sh", "step-java", stepCallbacks)
        SetupStep.DownloadRuneLite -> stepRunner.executeModularScript("download-runelite.sh", "step-runelite", stepCallbacks)
        SetupStep.InstallGpuDrivers -> stepRunner.executeGpuStep(stepCallbacks)
        SetupStep.VerifySetup -> {
            _currentOutput.value = "Verifying setup..."
            val result = verifier.verify(); _currentOutput.value = result.output
            if (!result.success) updateCurrentStepStatus(StepStatus.Failed("Verification failed: ${result.failures.joinToString(", ")}"))
            result.success
        }
    }

    private suspend fun handlePermissionsStep(): Boolean {
        val alreadyWorking = permissionChecker.verifyPermissions()
        if (alreadyWorking && permissionChecker.hasRequiredBatteryExemptions()) { _permissionPhase.value = PermissionPhase.Complete; return true }
        _permissionPhase.value = if (alreadyWorking) PermissionPhase.BatteryOptimization else PermissionPhase.TermuxConfig
        _awaitingPermissionCompletion.value = true
        val i = _steps.value.indexOfFirst { it.step == SetupStep.EnablePermissions }
        updateStepStatus(i, StepStatus.InProgress); _currentOutput.value = null; failedStepIndex = i
        return false
    }

    suspend fun advancePermissionPhase(): Boolean = when (_permissionPhase.value) {
        is PermissionPhase.TermuxConfig -> {
            _permissionPhase.value = PermissionPhase.RuntimePermission; actions?.requestTermuxPermission()
            if (permissionChecker.verifyPermissions()) { _permissionPhase.value = PermissionPhase.BatteryOptimization; requestBatteryOptimization() }
            true
        }
        is PermissionPhase.RuntimePermission -> if (permissionChecker.verifyPermissions()) { _permissionPhase.value = PermissionPhase.BatteryOptimization; true } else false
        is PermissionPhase.BatteryOptimization -> if (permissionChecker.getNextBatteryOptimizationPackage() == null) { _permissionPhase.value = PermissionPhase.Complete; completePermissionsStep(); true } else false
        is PermissionPhase.Complete -> true
    }

    fun requestBatteryOptimization() {
        val pkg = permissionChecker.getNextBatteryOptimizationPackage() ?: PermissionHandler.TERMUX_PACKAGE
        try {
            if (pkg == PermissionHandler.TERMUX_X11_PACKAGE) actions?.openAppSettings(pkg)
            else actions?.requestBatteryOptimization(pkg)
        } catch (_: Exception) { actions?.openAppSettings(pkg) }
    }

    fun requestOwnBatteryOptimization() {}
    fun hasNotificationPermission(): Boolean = permissionChecker.hasNotificationPermission()

    private suspend fun completePermissionsStep() {
        _awaitingPermissionCompletion.value = false
        val i = _steps.value.indexOfFirst { it.step == SetupStep.EnablePermissions }
        if (i >= 0) { updateStepStatus(i, StepStatus.Completed); runSetupFrom(i + 1) }
    }

    private suspend fun evaluateCompletedSteps() {
        val (t, tx) = withContext(ioDispatcher) { Pair(packageChecker.isTermuxInstalled(), packageChecker.isTermuxX11Installed()) }
        _steps.update { it.map { s -> when (s.step) {
            SetupStep.InstallTermux -> if (t) s.copy(status = StepStatus.Completed) else s
            SetupStep.InstallTermuxX11 -> if (tx) s.copy(status = StepStatus.Completed) else s
            SetupStep.InstallProot -> if (stateStore.isCompleted("step-proot")) s.copy(status = StepStatus.Completed) else s
            SetupStep.InstallJava -> if (stateStore.isCompleted("step-java")) s.copy(status = StepStatus.Completed) else s
            SetupStep.DownloadRuneLite -> if (stateStore.isCompleted("step-runelite")) s.copy(status = StepStatus.Completed) else s
            SetupStep.InstallGpuDrivers -> if (stateStore.isCompleted("step-gpu")) s.copy(status = StepStatus.Completed) else s
            else -> s
        }}}
    }

    private suspend fun reconcileWithMarkers() {
        val result = reconciler.reconcile() ?: return
        if (result.versionMismatch) {
            stateStore.clearAll()
            _steps.update { it.map { s -> if (MarkerReconciler.MODULAR_STEPS.containsKey(s.step)) s.copy(status = StepStatus.Pending) else s } }
            return
        }
        for (key in result.presentKeys) {
            val step = MarkerReconciler.MODULAR_STEPS.entries.find { it.value.first == key }?.key ?: continue
            stateStore.markCompleted(key)
            val i = _steps.value.indexOfFirst { it.step == step }; if (i >= 0) updateStepStatus(i, StepStatus.Completed)
        }
        for (key in result.absentKeys) {
            val step = MarkerReconciler.MODULAR_STEPS.entries.find { it.value.first == key }?.key ?: continue
            val i = _steps.value.indexOfFirst { it.step == step }
            if (i >= 0 && _steps.value[i].status is StepStatus.Completed) { updateStepStatus(i, StepStatus.Pending); stateStore.clearCompleted(key) }
        }
        stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
    }

    private fun updateStepStatus(index: Int, status: StepStatus) {
        _steps.update { it.toMutableList().also { l -> l[index] = l[index].copy(status = status) } }
    }

    private fun updateCurrentStepStatus(status: StepStatus) {
        val current = _currentStep.value ?: return
        val index = _steps.value.indexOfFirst { it.step == current }
        if (index >= 0) updateStepStatus(index, status)
    }
}

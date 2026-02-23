package com.runelitetablet.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runelitetablet.cleanup.CleanupManager
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

class SetupViewModel(
    private val context: Context,
    private val orchestrator: SetupOrchestrator,
    private val commandRunner: TermuxCommandRunner,
    private val scriptManager: ScriptManager
) : ViewModel() {

    val steps: StateFlow<List<StepState>> = orchestrator.steps
    val currentOutput: StateFlow<String?> = orchestrator.currentOutput

    val canLaunch: StateFlow<Boolean> = orchestrator.steps
        .map { steps -> steps.all { it.status is StepStatus.Completed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _showPermissionsSheet = MutableStateFlow(false)
    val showPermissionsSheet: StateFlow<Boolean> = _showPermissionsSheet.asStateFlow()

    private val _permissionInstructions = MutableStateFlow<List<String>>(emptyList())
    val permissionInstructions: StateFlow<List<String>> = _permissionInstructions.asStateFlow()

    private val setupStarted = AtomicBoolean(false)
    private val isRetrying = AtomicBoolean(false)

    fun startSetup() {
        val wasAlreadyStarted = !setupStarted.compareAndSet(false, true)
        AppLog.step("setup", "startSetup called: compareAndSet result=started wasAlreadyStarted=$wasAlreadyStarted")
        if (wasAlreadyStarted) return
        AppLog.step("setup", "startSetup: launching coroutine in viewModelScope")
        viewModelScope.launch {
            AppLog.perf("startSetup: coroutine started thread=${Thread.currentThread().name}")
            AppLog.step("setup", "startSetup coroutine: entry thread=${Thread.currentThread().name}")
            orchestrator.runSetup()
            AppLog.step("setup", "startSetup coroutine: exit")
            AppLog.perf("startSetup: coroutine completed")
        }
    }

    fun retry() {
        if (!isRetrying.compareAndSet(false, true)) return
        val failedStep = orchestrator.steps.value.firstOrNull { it.status is StepStatus.Failed }
        AppLog.step(failedStep?.step?.id ?: "unknown", "retry requested for step=${failedStep?.step?.id}")
        viewModelScope.launch {
            try {
                AppLog.perf("retry: coroutine started")
                orchestrator.retryCurrentStep()
                AppLog.perf("retry: coroutine completed")
            } finally {
                isRetrying.set(false)
            }
        }
    }

    fun launch() {
        AppLog.perf("launch: started")
        // Set Termux:X11 preferences before launch so the display is fullscreen from the start.
        // Preferences persist across Termux:X11 restarts, so subsequent launches also benefit.
        // The shell script also sets these as a backup in case this broadcast is missed.
        val prefIntent = Intent("com.termux.x11.CHANGE_PREFERENCE").apply {
            setPackage("com.termux.x11")
            putExtra("fullscreen", "true")
            putExtra("showAdditionalKbd", "false")
            putExtra("displayResolutionMode", "native")
        }
        context.sendBroadcast(prefIntent)
        AppLog.step("launch", "launch: sent CHANGE_PREFERENCE broadcast to Termux:X11")

        val scriptPath = scriptManager.getScriptPath("launch-runelite.sh")
        AppLog.step("launch", "launch: attempting RuneLite launch scriptPath=$scriptPath")
        val success = commandRunner.launch(
            commandPath = scriptPath,
            sessionAction = TermuxCommandRunner.SESSION_ACTION_SWITCH_NEW
        )
        AppLog.step("launch", "launch: result success=$success")
        if (!success) {
            AppLog.e("STEP", "launch: failed to start RuneLite launch command")
        }
        AppLog.perf("launch: completed success=$success")
    }

    fun onManualStepClick(stepState: StepState) {
        val status = stepState.status
        AppLog.state("onManualStepClick: stepId=${stepState.step.id} status=${status::class.simpleName}")
        if (status is StepStatus.ManualAction) {
            _permissionInstructions.value = status.instructions
            _showPermissionsSheet.value = true
            AppLog.state("onManualStepClick: showing permissions sheet instructionCount=${status.instructions.size}")
        }
    }

    fun dismissPermissionsSheet() {
        AppLog.state("dismissPermissionsSheet: hiding permissions sheet")
        _showPermissionsSheet.value = false
    }

    fun verifyPermissions() {
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                _showPermissionsSheet.value = false
                val verified = orchestrator.verifyPermissions()
                if (verified) {
                    orchestrator.retryCurrentStep()
                }
            } finally {
                isRetrying.set(false)
            }
        }
    }

    fun recheckPermissions() {
        // Re-evaluate completed steps on return from settings/install screens.
        // Guard with isRetrying to prevent concurrent retryCurrentStep() calls if the user
        // navigates back rapidly (e.g., quick back-press from install confirmation screen).
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val currentSteps = orchestrator.steps.value
                val hasFailedInstall = currentSteps.any {
                    (it.step == SetupStep.InstallTermux || it.step == SetupStep.InstallTermuxX11) &&
                        it.status is StepStatus.Failed
                }
                if (hasFailedInstall) {
                    orchestrator.retryCurrentStep()
                }
            } finally {
                isRetrying.set(false)
            }
        }
    }

    fun bindActions(activity: Activity) {
        orchestrator.actions = object : SetupActions {
            override fun requestInstallPermission() {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }

            override fun requestTermuxPermission() {
                val permission = "com.termux.permission.RUN_COMMAND"
                if (activity.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(permission), REQUEST_TERMUX_PERMISSION)
                }
            }

            override fun launchIntent(intent: Intent) {
                activity.startActivity(intent)
            }
        }
    }

    fun unbindActions() {
        orchestrator.actions = null
    }

    companion object {
        const val REQUEST_TERMUX_PERMISSION = 1001
    }

    class Factory(
        activity: Activity,
        private val httpClient: OkHttpClient
    ) : ViewModelProvider.Factory {
        // Store applicationContext only — storing Activity would leak it across config changes
        // since ViewModelProvider retains the Factory for the lifetime of the ViewModelStore.
        private val appContext: Context = activity.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val context = appContext
            val termuxHelper = TermuxPackageHelper(context)
            val apkDownloader = ApkDownloader(context, httpClient)
            val apkInstaller = ApkInstaller(context)
            val commandRunner = TermuxCommandRunner(context)
            val scriptManager = ScriptManager(context, commandRunner)
            val cleanupManager = CleanupManager(context)
            val orchestrator = SetupOrchestrator(
                context, termuxHelper, apkDownloader, apkInstaller,
                commandRunner, scriptManager, cleanupManager
            )
            return SetupViewModel(context, orchestrator, commandRunner, scriptManager) as T
        }
    }
}

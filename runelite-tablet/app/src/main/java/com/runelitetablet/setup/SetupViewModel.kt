package com.runelitetablet.setup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
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

    fun startSetup() {
        if (!setupStarted.compareAndSet(false, true)) return
        viewModelScope.launch {
            orchestrator.runSetup()
        }
    }

    fun retry() {
        viewModelScope.launch {
            orchestrator.retryCurrentStep()
        }
    }

    fun launch() {
        val success = commandRunner.launch(
            commandPath = scriptManager.getScriptPath("launch-runelite.sh"),
            sessionAction = TermuxCommandRunner.SESSION_ACTION_SWITCH_NEW
        )
        if (!success) {
            // Could update a state to show error, for now just log
        }
    }

    fun onManualStepClick(stepState: StepState) {
        val status = stepState.status
        if (status is StepStatus.ManualAction) {
            _permissionInstructions.value = status.instructions
            _showPermissionsSheet.value = true
        }
    }

    fun dismissPermissionsSheet() {
        _showPermissionsSheet.value = false
    }

    fun verifyPermissions() {
        viewModelScope.launch {
            _showPermissionsSheet.value = false
            val verified = orchestrator.verifyPermissions()
            if (verified) {
                orchestrator.retryCurrentStep()
            }
        }
    }

    fun recheckPermissions() {
        // Re-evaluate completed steps on return from settings/install screens
        viewModelScope.launch {
            val currentSteps = orchestrator.steps.value
            val hasFailedInstall = currentSteps.any {
                (it.step == SetupStep.InstallTermux || it.step == SetupStep.InstallTermuxX11) &&
                    it.status is StepStatus.Failed
            }
            if (hasFailedInstall) {
                orchestrator.retryCurrentStep()
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

            override fun launchIntent(intent: Intent) {
                activity.startActivity(intent)
            }
        }
    }

    fun unbindActions() {
        orchestrator.actions = null
    }

    class Factory(
        private val activity: Activity,
        private val httpClient: OkHttpClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val context = activity.applicationContext
            val termuxHelper = TermuxPackageHelper(context)
            val apkDownloader = ApkDownloader(context, httpClient)
            val apkInstaller = ApkInstaller(context)
            val commandRunner = TermuxCommandRunner(context)
            val scriptManager = ScriptManager(context, commandRunner)
            val orchestrator = SetupOrchestrator(
                context, termuxHelper, apkDownloader, apkInstaller,
                commandRunner, scriptManager
            )
            return SetupViewModel(orchestrator, commandRunner, scriptManager) as T
        }
    }
}

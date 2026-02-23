package com.runelitetablet.setup

sealed class SetupStep(val id: String, val label: String) {
    object InstallTermux : SetupStep("termux", "Install Termux")
    object InstallTermuxX11 : SetupStep("termux_x11", "Install Termux:X11")
    object EnablePermissions : SetupStep("permissions", "Configure Permissions")
    object InstallProot : SetupStep("proot", "Install Linux Environment")
    object InstallJava : SetupStep("java", "Install Java Runtime")
    object DownloadRuneLite : SetupStep("runelite", "Download RuneLite")
    object VerifySetup : SetupStep("verify", "Verify Setup")

    companion object {
        val allSteps: List<SetupStep> by lazy {
            listOf(
                InstallTermux, InstallTermuxX11, EnablePermissions,
                InstallProot, InstallJava, DownloadRuneLite, VerifySetup
            )
        }
    }
}

sealed class StepStatus {
    object Pending : StepStatus()
    object InProgress : StepStatus()
    object Completed : StepStatus()
    data class Failed(val message: String) : StepStatus()
    data class ManualAction(val instructions: List<String>) : StepStatus()

    val displayText: String
        get() = when (this) {
            Pending -> "Pending"
            InProgress -> "Running"
            Completed -> "Done"
            is Failed -> "Failed"
            is ManualAction -> "Action Required"
        }
}

/**
 * Top-level setup state. Controls which screen/content is shown.
 * Reconciling prevents step-flicker during startup marker check.
 */
sealed class SetupState {
    /** Loading cached state, reconciling against markers. Buttons disabled. */
    object Reconciling : SetupState()
    /** Setup wizard is active (steps running or waiting). */
    object SetupInProgress : SetupState()
    /** All setup steps completed. */
    object SetupComplete : SetupState()
}

data class StepState(
    val step: SetupStep,
    val status: StepStatus = StepStatus.Pending
)

/**
 * Tracks the current phase within the multi-phase permissions step.
 * After all 3 phases complete, the permissions step is marked Completed.
 */
sealed class PermissionPhase {
    /** Phase 1: User must paste a command in Termux to enable allow-external-apps */
    object TermuxConfig : PermissionPhase()
    /** Phase 2: Request the RUN_COMMAND runtime permission via system dialog */
    object RuntimePermission : PermissionPhase()
    /** Phase 3: Request battery optimization exemption for Termux and our app */
    object BatteryOptimization : PermissionPhase()
    /** All permission phases completed successfully */
    object Complete : PermissionPhase()
}

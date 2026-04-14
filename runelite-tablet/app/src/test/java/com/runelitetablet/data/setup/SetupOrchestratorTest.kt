package com.runelitetablet.data.setup

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.setup.MarkerReconciler
import com.runelitetablet.setup.PermissionPhase
import com.runelitetablet.setup.SetupOrchestrator
import com.runelitetablet.setup.SetupStep
import com.runelitetablet.setup.SetupStepRunner
import com.runelitetablet.setup.SetupVerifier
import com.runelitetablet.setup.StepStatus
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.testutil.FakeCommandRunner
import com.runelitetablet.testutil.FakeCleaner
import com.runelitetablet.testutil.FakePackageChecker
import com.runelitetablet.testutil.FakePermissionChecker
import com.runelitetablet.testutil.FakeScriptDeployer
import com.runelitetablet.testutil.FakeSetupStateStore
import com.runelitetablet.testutil.MainDispatcherRule
import com.runelitetablet.testutil.PrintLogger
import com.runelitetablet.testutil.UnsafeHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for SetupOrchestrator — the top-level setup coordinator.
 *
 * Uses FakePermissionChecker to avoid Android Context dependency.
 * Uses Unsafe-allocated ApkDownloader/ApkInstaller (never called directly).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupOrchestratorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var commandRunner: FakeCommandRunner
    private lateinit var packageChecker: FakePackageChecker
    private lateinit var scriptDeployer: FakeScriptDeployer
    private lateinit var stateStore: FakeSetupStateStore
    private lateinit var cleaner: FakeCleaner
    private lateinit var logger: PrintLogger
    private lateinit var permissionChecker: FakePermissionChecker

    private lateinit var stepRunner: SetupStepRunner
    private lateinit var verifier: SetupVerifier
    private lateinit var reconciler: MarkerReconciler
    private lateinit var orchestrator: SetupOrchestrator

    // ── Setup ───────────────────────────────────────────────────────────

    @Before
    fun setup() {
        commandRunner = FakeCommandRunner()
        packageChecker = FakePackageChecker()
        scriptDeployer = FakeScriptDeployer()
        stateStore = FakeSetupStateStore()
        cleaner = FakeCleaner()
        logger = PrintLogger()
        permissionChecker = FakePermissionChecker()

        commandRunner.setDefaultResult(
            CommandResult(stdout = "", stderr = null, exitCode = 0, error = null)
        )

        stepRunner = SetupStepRunner(
            commandRunner = commandRunner,
            packageChecker = packageChecker,
            apkDownloader = UnsafeHelper.allocate(ApkDownloader::class.java),
            apkInstaller = UnsafeHelper.allocate(ApkInstaller::class.java),
            scriptDeployer = scriptDeployer,
            stateStore = stateStore,
            logger = logger
        )

        verifier = SetupVerifier(commandRunner, logger)
        reconciler = MarkerReconciler(commandRunner, scriptDeployer, logger)

        orchestrator = createOrchestrator()
    }

    private fun createOrchestrator(): SetupOrchestrator = SetupOrchestrator(
        packageChecker = packageChecker,
        stepRunner = stepRunner,
        verifier = verifier,
        reconciler = reconciler,
        cleaner = cleaner,
        stateStore = stateStore,
        permissionChecker = permissionChecker,
        apkInstaller = UnsafeHelper.allocate(ApkInstaller::class.java),
        ioDispatcher = UnconfinedTestDispatcher(),
        logger = logger
    )

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun preInstallPackages() {
        packageChecker.setInstalled("com.termux")
        packageChecker.setInstalled("com.termux.x11")
    }

    private fun grantPermissions() {
        permissionChecker.permissionsVerified = true
        permissionChecker.batteryExemptionsComplete = true
    }

    private fun denyPermissions() {
        permissionChecker.permissionsVerified = false
        permissionChecker.batteryExemptionsComplete = false
    }

    private fun markModularStepsCompleted() {
        stateStore.markCompleted("step-proot")
        stateStore.markCompleted("step-java")
        stateStore.markCompleted("step-runelite")
        stateStore.markCompleted("step-gpu")
        stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
    }

    private fun setUpScriptSuccess(scriptName: String) {
        val markerName = scriptName.removeSuffix(".sh")
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath(scriptName),
            CommandResult(
                stdout = "=== $markerName complete ===",
                stderr = null, exitCode = 0, error = null
            )
        )
    }

    private fun setUpGpuSuccess() {
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("setup-gpu.sh"),
            CommandResult(
                stdout = "GPU_SETUP_COMPLETE",
                stderr = null, exitCode = 0, error = null
            )
        )
    }

    private fun setUpVerifySuccess() {
        commandRunner.whenCommand(
            "${CommandRunner.TERMUX_BIN_PATH}/bash",
            CommandResult(
                stdout = "PASS: proot\nPASS: java\nPASS: runelite\nPASS: x11",
                stderr = null, exitCode = 0, error = null
            )
        )
    }

    /** Disable reconciler by failing script deployment. */
    private fun disableReconciler() {
        scriptDeployer.deployScriptsResult = false
    }

    private fun stepStatus(stepId: String): StepStatus =
        orchestrator.steps.value.first { it.step.id == stepId }.status

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `fresh install runs all steps and completes`() = runTest {
        preInstallPackages()
        grantPermissions()
        disableReconciler()

        setUpScriptSuccess("install-proot.sh")
        setUpScriptSuccess("install-java.sh")
        setUpScriptSuccess("download-runelite.sh")
        setUpGpuSuccess()
        setUpVerifySuccess()

        // Re-enable script deployer for modular steps (but not reconciler,
        // since reconciler runs before these steps and was already disabled).
        scriptDeployer.deployScriptsResult = true

        orchestrator.runSetup()

        for (state in orchestrator.steps.value) {
            assertEquals(
                "Step ${state.step.id} should be Completed",
                StepStatus.Completed, state.status
            )
        }
        assertTrue(cleaner.cleanupCalled)
    }

    @Test
    fun `cached steps are skipped`() = runTest {
        preInstallPackages()
        grantPermissions()
        markModularStepsCompleted()
        disableReconciler()
        setUpVerifySuccess()

        orchestrator.runSetup()

        assertEquals(StepStatus.Completed, stepStatus("proot"))
        assertEquals(StepStatus.Completed, stepStatus("java"))
        assertEquals(StepStatus.Completed, stepStatus("runelite"))
        assertEquals(StepStatus.Completed, stepStatus("gpu"))

        val scriptCommands = commandRunner.executedCommands.filter {
            it.commandPath.contains("/fake/scripts/")
        }
        assertEquals("No modular scripts should have executed", 0, scriptCommands.size)
    }

    @Test
    fun `version mismatch in state store clears cached state`() = runTest {
        preInstallPackages()
        denyPermissions()
        stateStore.markCompleted("step-proot")
        stateStore.markCompleted("step-java")
        stateStore.setStoredVersion("old-version")
        disableReconciler()

        orchestrator.runSetup()

        assertFalse(stateStore.isCompleted("step-proot"))
        assertFalse(stateStore.isCompleted("step-java"))
        assertEquals(StepStatus.Pending, stepStatus("proot"))
        assertEquals(StepStatus.Pending, stepStatus("java"))
    }

    @Test
    fun `permission step waits for callback before advancing`() = runTest {
        preInstallPackages()
        denyPermissions()
        disableReconciler()

        orchestrator.runSetup()

        assertEquals(StepStatus.InProgress, stepStatus("permissions"))
        assertTrue(orchestrator.awaitingPermissionCompletion.value)
        assertEquals(StepStatus.Pending, stepStatus("proot"))
        assertEquals(StepStatus.Pending, stepStatus("java"))
        assertEquals(StepStatus.Pending, stepStatus("verify"))
    }

    @Test
    fun `retry failed step re-executes only that step`() = runTest {
        preInstallPackages()
        grantPermissions()
        stateStore.markCompleted("step-java")
        stateStore.markCompleted("step-runelite")
        stateStore.markCompleted("step-gpu")
        stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
        scriptDeployer.deployScriptsResult = true

        // Reconciler returns no markers (all absent, version matches)
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("check-markers.sh"),
            CommandResult(
                stdout = "VERSION ${SetupStateStore.CURRENT_SCRIPT_VERSION}\n" +
                    "ABSENT step-proot\nPRESENT step-java\n" +
                    "PRESENT step-runelite\nPRESENT step-gpu",
                stderr = null, exitCode = 0, error = null
            )
        )

        // Proot script fails with non-zero exit and no completion marker
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("install-proot.sh"),
            CommandResult(stdout = "partial output", stderr = "network error", exitCode = 1, error = null)
        )

        orchestrator.runSetup()

        // executeModularScript returns false on failure, which means runSetupFrom
        // stops without marking Failed. The step stays InProgress with failedStepIndex set.
        val prootStatus = stepStatus("proot")
        assertTrue(
            "Proot step should be InProgress (blocked): $prootStatus",
            prootStatus is StepStatus.InProgress
        )

        // Fix: set up proot to succeed
        setUpScriptSuccess("install-proot.sh")
        setUpVerifySuccess()

        orchestrator.retryCurrentStep()

        assertEquals(StepStatus.Completed, stepStatus("proot"))
        // Should have continued to verify after retry succeeded
        assertEquals(StepStatus.Completed, stepStatus("verify"))
    }

    @Test
    fun `verifySetup step delegates to verifier`() = runTest {
        preInstallPackages()
        grantPermissions()
        markModularStepsCompleted()
        disableReconciler()
        setUpVerifySuccess()

        orchestrator.runSetup()

        assertEquals(StepStatus.Completed, stepStatus("verify"))
        val bashCommands = commandRunner.executedCommands.filter {
            it.commandPath.contains("/bash")
        }
        assertTrue("Verifier should execute bash check", bashCommands.isNotEmpty())
    }

    @Test
    fun `verifySetup fails when verifier reports failure`() = runTest {
        preInstallPackages()
        grantPermissions()
        markModularStepsCompleted()
        disableReconciler()

        commandRunner.whenCommand(
            "${CommandRunner.TERMUX_BIN_PATH}/bash",
            CommandResult(
                stdout = "PASS: proot\nFAIL: java\nPASS: runelite\nPASS: x11",
                stderr = null, exitCode = 0, error = null
            )
        )

        orchestrator.runSetup()

        assertTrue(
            "Verify step should have failed: ${stepStatus("verify")}",
            stepStatus("verify") is StepStatus.Failed
        )
    }

    @Test
    fun `GPU step is non-blocking — always returns true even on failure`() = runTest {
        preInstallPackages()
        grantPermissions()
        stateStore.markCompleted("step-proot")
        stateStore.markCompleted("step-java")
        stateStore.markCompleted("step-runelite")
        stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
        disableReconciler()
        scriptDeployer.deployScriptsResult = true

        // GPU script fails
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("setup-gpu.sh"),
            CommandResult(
                stdout = "mesa build failed",
                stderr = "compilation error",
                exitCode = 1,
                error = null
            )
        )
        setUpVerifySuccess()

        orchestrator.runSetup()

        assertEquals(StepStatus.Completed, stepStatus("gpu"))
        assertEquals(StepStatus.Completed, stepStatus("verify"))
    }

    @Test
    fun `evaluateCompletedSteps marks installed packages as completed`() = runTest {
        packageChecker.setInstalled("com.termux")
        packageChecker.setInstalled("com.termux.x11")
        stateStore.markCompleted("step-proot")
        stateStore.markCompleted("step-java")
        stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)

        denyPermissions()
        disableReconciler()

        orchestrator.runSetup()

        assertEquals(StepStatus.Completed, stepStatus("termux"))
        assertEquals(StepStatus.Completed, stepStatus("termux_x11"))
        assertEquals(StepStatus.Completed, stepStatus("proot"))
        assertEquals(StepStatus.Completed, stepStatus("java"))
        // runelite was NOT marked in stateStore
        assertEquals(StepStatus.Pending, stepStatus("runelite"))
    }

    @Test
    fun `reconcileWithMarkers syncs marker presence with step state`() = runTest {
        preInstallPackages()
        denyPermissions()
        scriptDeployer.deployScriptsResult = true

        val markerOutput = buildString {
            appendLine("VERSION ${SetupStateStore.CURRENT_SCRIPT_VERSION}")
            appendLine("PRESENT step-proot")
            appendLine("PRESENT step-java")
            appendLine("ABSENT step-runelite")
            appendLine("ABSENT step-gpu")
        }
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("check-markers.sh"),
            CommandResult(stdout = markerOutput, stderr = null, exitCode = 0, error = null)
        )

        orchestrator.runSetup()

        assertTrue(stateStore.isCompleted("step-proot"))
        assertTrue(stateStore.isCompleted("step-java"))
        assertEquals(StepStatus.Completed, stepStatus("proot"))
        assertEquals(StepStatus.Completed, stepStatus("java"))
        // Absent markers should not be completed
        assertFalse(stateStore.isCompleted("step-runelite"))
    }

    @Test
    fun `reconcileWithMarkers clears completed state for absent markers`() = runTest {
        preInstallPackages()
        denyPermissions()
        stateStore.markCompleted("step-proot")
        stateStore.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
        scriptDeployer.deployScriptsResult = true

        val markerOutput = buildString {
            appendLine("VERSION ${SetupStateStore.CURRENT_SCRIPT_VERSION}")
            appendLine("ABSENT step-proot")
            appendLine("ABSENT step-java")
            appendLine("ABSENT step-runelite")
            appendLine("ABSENT step-gpu")
        }
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("check-markers.sh"),
            CommandResult(stdout = markerOutput, stderr = null, exitCode = 0, error = null)
        )

        orchestrator.runSetup()

        assertFalse(stateStore.isCompleted("step-proot"))
        assertEquals(StepStatus.Pending, stepStatus("proot"))
    }

    @Test
    fun `reconcileWithMarkers version mismatch resets modular steps to Pending`() = runTest {
        preInstallPackages()
        denyPermissions()
        markModularStepsCompleted()
        scriptDeployer.deployScriptsResult = true

        val markerOutput = buildString {
            appendLine("VERSION 1")
            appendLine("PRESENT step-proot")
            appendLine("PRESENT step-java")
            appendLine("ABSENT step-runelite")
            appendLine("ABSENT step-gpu")
        }
        commandRunner.whenCommand(
            scriptDeployer.getScriptPath("check-markers.sh"),
            CommandResult(stdout = markerOutput, stderr = null, exitCode = 0, error = null)
        )

        orchestrator.runSetup()

        assertEquals(StepStatus.Pending, stepStatus("proot"))
        assertEquals(StepStatus.Pending, stepStatus("java"))
        assertEquals(StepStatus.Pending, stepStatus("runelite"))
        assertEquals(StepStatus.Pending, stepStatus("gpu"))
    }

    @Test
    fun `resetState clears all state to initial values`() = runTest {
        preInstallPackages()
        denyPermissions()
        markModularStepsCompleted()
        disableReconciler()

        orchestrator.runSetup()

        assertTrue(orchestrator.steps.value.any { it.status == StepStatus.Completed })

        orchestrator.resetState()

        for (state in orchestrator.steps.value) {
            assertEquals(
                "Step ${state.step.id} should be Pending after reset",
                StepStatus.Pending, state.status
            )
        }
        assertEquals(PermissionPhase.TermuxConfig, orchestrator.permissionPhase.value)
        assertFalse(orchestrator.awaitingPermissionCompletion.value)
        assertNull(orchestrator.currentOutput.value)
    }

    @Test
    fun `runSetup calls cleaner cleanup`() = runTest {
        denyPermissions()
        disableReconciler()

        orchestrator.runSetup()

        assertTrue("Cleaner should have been called", cleaner.cleanupCalled)
    }

    @Test
    fun `retry with no failed step is a no-op`() = runTest {
        val stepsBefore = orchestrator.steps.value.map { it.status }

        orchestrator.retryCurrentStep()

        val stepsAfter = orchestrator.steps.value.map { it.status }
        assertEquals(stepsBefore, stepsAfter)
    }

    @Test
    fun `permission step sets awaitingPermissionCompletion when blocked`() = runTest {
        preInstallPackages()
        denyPermissions()
        disableReconciler()

        assertFalse(orchestrator.awaitingPermissionCompletion.value)

        orchestrator.runSetup()

        assertTrue(orchestrator.awaitingPermissionCompletion.value)
        assertEquals(PermissionPhase.TermuxConfig, orchestrator.permissionPhase.value)
    }
}

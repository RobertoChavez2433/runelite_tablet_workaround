package com.runelitetablet.data.setup

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.setup.SetupStepRunner
import com.runelitetablet.testutil.FakeCommandRunner
import com.runelitetablet.testutil.FakePackageChecker
import com.runelitetablet.testutil.FakeScriptDeployer
import com.runelitetablet.testutil.FakeSetupStateStore
import com.runelitetablet.testutil.PrintLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for SetupStepRunner.executeModularScript() and executeGpuStep().
 *
 * installPackage() is NOT tested here because ApkDownloader and ApkInstaller
 * are concrete classes requiring Android Context and OkHttpClient. Those need
 * interface extraction before they can be faked. Tracked as a known gap.
 */
class SetupStepRunnerTest {

    private lateinit var commandRunner: FakeCommandRunner
    private lateinit var packageChecker: FakePackageChecker
    private lateinit var scriptDeployer: FakeScriptDeployer
    private lateinit var stateStore: FakeSetupStateStore
    private lateinit var logger: PrintLogger
    private lateinit var callbacks: RecordingCallbacks

    /** Captures callback invocations for assertions. */
    private class RecordingCallbacks : SetupStepRunner.Callbacks {
        val outputs = mutableListOf<String?>()
        var installPermissionRequested = false
        val launchedIntents = mutableListOf<android.content.Intent>()

        override fun updateOutput(message: String?) { outputs.add(message) }
        override fun requestInstallPermission() { installPermissionRequested = true }
        override fun launchIntent(intent: android.content.Intent) { launchedIntents.add(intent) }
    }

    /**
     * Creates a SetupStepRunner without real ApkDownloader/ApkInstaller.
     * Uses JVM Unsafe.allocateInstance via reflection to create uninitialized
     * placeholders. The methods under test never access these fields.
     */
    @Suppress("UNCHECKED_CAST")
    private fun createStepRunner(): SetupStepRunner {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)

        val apkDownloader = allocateMethod.invoke(unsafe,
            Class.forName("com.runelitetablet.installer.ApkDownloader")
        ) as com.runelitetablet.installer.ApkDownloader

        val apkInstaller = allocateMethod.invoke(unsafe,
            Class.forName("com.runelitetablet.installer.ApkInstaller")
        ) as com.runelitetablet.installer.ApkInstaller

        return SetupStepRunner(
            commandRunner = commandRunner,
            packageChecker = packageChecker,
            apkDownloader = apkDownloader,
            apkInstaller = apkInstaller,
            scriptDeployer = scriptDeployer,
            stateStore = stateStore,
            logger = logger
        )
    }

    @Before
    fun setup() {
        commandRunner = FakeCommandRunner()
        packageChecker = FakePackageChecker()
        scriptDeployer = FakeScriptDeployer()
        stateStore = FakeSetupStateStore()
        logger = PrintLogger()
        callbacks = RecordingCallbacks()
    }

    // ── executeModularScript ────────────────────────────────────────────

    @Test
    fun `executeModularScript succeeds on zero exit code`() = runTest {
        val completionMarker = "=== install-proot complete ==="
        commandRunner.setDefaultResult(CommandResult(
            stdout = "some output\n$completionMarker",
            stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertTrue(result)
        assertTrue(stateStore.isCompleted("step-proot"))
        assertEquals(SetupStateStore.CURRENT_SCRIPT_VERSION, stateStore.getStoredVersion())
    }

    @Test
    fun `executeModularScript succeeds on non-zero exit when completion marker present`() = runTest {
        val completionMarker = "=== install-java complete ==="
        commandRunner.setDefaultResult(CommandResult(
            stdout = "warnings...\n$completionMarker\nmore output",
            stderr = null, exitCode = 1, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-java.sh", "step-java", callbacks)

        assertTrue(result)
        assertTrue(stateStore.isCompleted("step-java"))
        // Should log a warning about non-zero exit
        assertTrue(logger.entries.any { it.level == "WARN" && it.message.contains("non-zero exit") })
    }

    @Test
    fun `executeModularScript fails on non-zero exit without completion marker`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "partial output without marker",
            stderr = "something broke", exitCode = 1, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertFalse(result)
        assertFalse(stateStore.isCompleted("step-proot"))
        // Error output should be sent to callbacks
        assertTrue(callbacks.outputs.any { it?.contains("something broke") == true })
    }

    @Test
    fun `executeModularScript fails when script deployment fails`() = runTest {
        scriptDeployer.deployScriptsResult = false

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertFalse(result)
        assertTrue(callbacks.outputs.any { it?.contains("Failed to deploy") == true })
        assertEquals(0, commandRunner.executedCommands.size)
    }

    @Test
    fun `executeModularScript fails when config deployment fails`() = runTest {
        scriptDeployer.deployConfigsResult = false

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertFalse(result)
        assertTrue(callbacks.outputs.any { it?.contains("Failed to deploy") == true })
    }

    @Test
    fun `executeModularScript uses correct script path`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "=== install-proot complete ===",
            stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertEquals(1, commandRunner.executedCommands.size)
        assertEquals("/fake/scripts/install-proot.sh", commandRunner.executedCommands[0].commandPath)
        assertTrue(commandRunner.executedCommands[0].background)
    }

    @Test
    fun `executeModularScript truncates long output to 2000 chars`() = runTest {
        val longOutput = "x".repeat(5000) + "\n=== install-proot complete ==="
        commandRunner.setDefaultResult(CommandResult(
            stdout = longOutput, stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        // The last output update should be truncated to 2000 chars
        val outputUpdate = callbacks.outputs.last { it != null && it.length > 100 }
        assertTrue((outputUpdate?.length ?: 0) <= 2000)
    }

    @Test
    fun `executeModularScript uses error field when stderr is null`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = null, stderr = null, exitCode = 1, error = "timeout reached"
        ))

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertFalse(result)
        assertTrue(callbacks.outputs.any { it == "timeout reached" })
    }

    @Test
    fun `executeModularScript shows Unknown error when both stderr and error are null`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = null, stderr = null, exitCode = 1, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        assertFalse(result)
        assertTrue(callbacks.outputs.any { it == "Unknown error" })
    }

    @Test
    fun `executeModularScript emits progress message before executing`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "=== install-proot complete ===",
            stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        runner.executeModularScript("install-proot.sh", "step-proot", callbacks)

        // First output after deployment should be progress message
        val progressMsg = callbacks.outputs.find { it?.contains("Running install-proot.sh") == true }
        assertNotNull(progressMsg)
    }

    // ── executeGpuStep ──────────────────────────────────────────────────

    @Test
    fun `executeGpuStep skips when already completed`() = runTest {
        stateStore.markCompleted("step-gpu")

        val runner = createStepRunner()
        val result = runner.executeGpuStep(callbacks)

        assertTrue(result)
        assertEquals(0, commandRunner.executedCommands.size)
    }

    @Test
    fun `executeGpuStep succeeds with GPU_SETUP_COMPLETE marker`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "installing drivers...\nGPU_SETUP_COMPLETE",
            stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeGpuStep(callbacks)

        assertTrue(result)
        assertTrue(stateStore.isCompleted("step-gpu"))
    }

    @Test
    fun `executeGpuStep succeeds on non-zero exit with GPU_SETUP_COMPLETE marker`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "GPU_SETUP_COMPLETE",
            stderr = null, exitCode = 1, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeGpuStep(callbacks)

        assertTrue(result)
        assertTrue(stateStore.isCompleted("step-gpu"))
    }

    @Test
    fun `executeGpuStep still returns true on failure and marks completed`() = runTest {
        // GPU step is non-blocking: even failures mark as completed
        commandRunner.setDefaultResult(CommandResult(
            stdout = "error: driver not found",
            stderr = "compilation error", exitCode = 1, error = null
        ))

        val runner = createStepRunner()
        val result = runner.executeGpuStep(callbacks)

        assertTrue(result)
        assertTrue(stateStore.isCompleted("step-gpu"))
        assertTrue(callbacks.outputs.any { it?.contains("GPU driver installation failed") == true })
    }

    @Test
    fun `executeGpuStep returns true when script deployment fails`() = runTest {
        // GPU step is non-blocking: deployment failure is not fatal
        scriptDeployer.deployScriptsResult = false

        val runner = createStepRunner()
        val result = runner.executeGpuStep(callbacks)

        assertTrue(result)
        assertEquals(0, commandRunner.executedCommands.size)
    }

    @Test
    fun `executeGpuStep uses correct script path`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "GPU_SETUP_COMPLETE",
            stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        runner.executeGpuStep(callbacks)

        assertEquals(1, commandRunner.executedCommands.size)
        assertEquals("/fake/scripts/setup-gpu.sh", commandRunner.executedCommands[0].commandPath)
    }

    @Test
    fun `executeGpuStep includes software rendering fallback message on failure`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = null, stderr = "mesa build failed", exitCode = 1, error = null
        ))

        val runner = createStepRunner()
        runner.executeGpuStep(callbacks)

        assertTrue(callbacks.outputs.any {
            it?.contains("software rendering will be used") == true
        })
    }

    @Test
    fun `executeGpuStep truncates long output to 2000 chars`() = runTest {
        val longOutput = "y".repeat(5000) + "\nGPU_SETUP_COMPLETE"
        commandRunner.setDefaultResult(CommandResult(
            stdout = longOutput, stderr = null, exitCode = 0, error = null
        ))

        val runner = createStepRunner()
        runner.executeGpuStep(callbacks)

        val longCallbackOutput = callbacks.outputs.filter { (it?.length ?: 0) > 100 }
        for (output in longCallbackOutput) {
            assertTrue((output?.length ?: 0) <= 2000)
        }
    }
}

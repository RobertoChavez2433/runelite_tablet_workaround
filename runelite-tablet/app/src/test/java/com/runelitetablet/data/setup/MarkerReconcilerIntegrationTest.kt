package com.runelitetablet.data.setup

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.setup.MarkerReconciler
import com.runelitetablet.testutil.FakeCommandRunner
import com.runelitetablet.testutil.FakeScriptDeployer
import com.runelitetablet.testutil.PrintLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for MarkerReconciler.reconcile() — the full suspend method,
 * not just the static parseOutput() (which is covered in MarkerReconcilerTest).
 */
class MarkerReconcilerIntegrationTest {

    private lateinit var commandRunner: FakeCommandRunner
    private lateinit var scriptDeployer: FakeScriptDeployer
    private lateinit var logger: PrintLogger
    private lateinit var reconciler: MarkerReconciler

    @Before
    fun setup() {
        commandRunner = FakeCommandRunner()
        scriptDeployer = FakeScriptDeployer()
        logger = PrintLogger()
        reconciler = MarkerReconciler(commandRunner, scriptDeployer, logger)
    }

    @Test
    fun `reconcile returns result with all markers present`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "VERSION ${SetupStateStore.CURRENT_SCRIPT_VERSION}\n" +
                "PRESENT step-proot\nPRESENT step-java\nPRESENT step-runelite\nPRESENT step-gpu",
            stderr = null, exitCode = 0, error = null
        ))

        val result = reconciler.reconcile()

        assertNotNull(result)
        assertEquals(
            setOf("step-proot", "step-java", "step-runelite", "step-gpu"),
            result!!.presentKeys
        )
        assertTrue(result.absentKeys.isEmpty())
        assertFalse(result.versionMismatch)
    }

    @Test
    fun `reconcile returns result with mixed present and absent markers`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "VERSION ${SetupStateStore.CURRENT_SCRIPT_VERSION}\n" +
                "PRESENT step-proot\nABSENT step-java\nPRESENT step-runelite\nABSENT step-gpu",
            stderr = null, exitCode = 0, error = null
        ))

        val result = reconciler.reconcile()

        assertNotNull(result)
        assertEquals(setOf("step-proot", "step-runelite"), result!!.presentKeys)
        assertEquals(setOf("step-java", "step-gpu"), result.absentKeys)
    }

    @Test
    fun `reconcile detects version mismatch`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "VERSION 1\nPRESENT step-proot",
            stderr = null, exitCode = 0, error = null
        ))

        val result = reconciler.reconcile()

        assertNotNull(result)
        assertTrue(result!!.versionMismatch)
    }

    @Test
    fun `reconcile returns null when script deployment fails`() = runTest {
        scriptDeployer.deployScriptsResult = false

        val result = reconciler.reconcile()

        assertNull(result)
        assertEquals(0, commandRunner.executedCommands.size)
        assertTrue(logger.entries.any { it.level == "WARN" && it.message.contains("script deployment failed") })
    }

    @Test
    fun `reconcile returns null on non-zero exit code`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "PRESENT step-proot",
            stderr = "permission denied", exitCode = 1, error = null
        ))

        val result = reconciler.reconcile()

        assertNull(result)
        assertTrue(logger.entries.any { it.level == "WARN" && it.message.contains("non-zero") })
    }

    @Test
    fun `reconcile uses correct script path`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "VERSION ${SetupStateStore.CURRENT_SCRIPT_VERSION}\nPRESENT step-proot",
            stderr = null, exitCode = 0, error = null
        ))

        reconciler.reconcile()

        assertEquals(1, commandRunner.executedCommands.size)
        assertEquals("/fake/scripts/check-markers.sh", commandRunner.executedCommands[0].commandPath)
        assertTrue(commandRunner.executedCommands[0].background)
    }

    @Test
    fun `reconcile passes correct timeout`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "PRESENT step-proot",
            stderr = null, exitCode = 0, error = null
        ))

        reconciler.reconcile()

        assertEquals(10_000L, commandRunner.executedCommands[0].timeoutMs)
    }

    @Test
    fun `reconcile handles empty stdout gracefully`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        val result = reconciler.reconcile()

        assertNotNull(result)
        assertTrue(result!!.presentKeys.isEmpty())
        assertTrue(result.absentKeys.isEmpty())
        assertFalse(result.versionMismatch)
    }

    @Test
    fun `reconcile handles null stdout gracefully`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = null, stderr = null, exitCode = 0, error = null
        ))

        val result = reconciler.reconcile()

        assertNotNull(result)
        assertTrue(result!!.presentKeys.isEmpty())
        assertTrue(result.absentKeys.isEmpty())
    }

    @Test
    fun `reconcile deploys scripts before executing command`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "PRESENT step-proot",
            stderr = null, exitCode = 0, error = null
        ))

        reconciler.reconcile()

        assertEquals(1, scriptDeployer.deployScriptsCalled)
    }

    @Test
    fun `reconcile returns null on generic exception`() = runTest {
        // Simulate an exception by configuring the command runner to throw
        // We use a path-specific result that won't match, causing default to apply
        commandRunner.setDefaultResult(CommandResult(
            stdout = null, stderr = null, exitCode = 0, error = null
        ))
        // Override the script deployer to deploy but make the command runner throw
        val throwingRunner = object : com.runelitetablet.domain.command.CommandRunner {
            override suspend fun execute(
                commandPath: String,
                arguments: Array<String>?,
                workdir: String?,
                background: Boolean,
                stdin: String?,
                timeoutMs: Long
            ): CommandResult {
                throw RuntimeException("connection reset")
            }
            override fun launch(commandPath: String, arguments: Array<String>?, sessionAction: String) = true
            override fun launchBackground(commandPath: String, arguments: Array<String>?) = true
        }
        val throwingReconciler = MarkerReconciler(throwingRunner, scriptDeployer, logger)

        val result = throwingReconciler.reconcile()

        assertNull(result)
        assertTrue(logger.entries.any { it.level == "WARN" && it.message.contains("exception") })
    }
}

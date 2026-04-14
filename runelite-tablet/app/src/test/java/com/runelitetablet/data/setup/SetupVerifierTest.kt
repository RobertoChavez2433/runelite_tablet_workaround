package com.runelitetablet.data.setup

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.setup.SetupVerifier
import com.runelitetablet.testutil.FakeCommandRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SetupVerifierTest {
    private lateinit var commandRunner: FakeCommandRunner
    private lateinit var verifier: SetupVerifier

    @Before
    fun setup() {
        commandRunner = FakeCommandRunner()
        verifier = SetupVerifier(commandRunner)
    }

    @Test
    fun `all checks pass returns success`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "PASS: proot\nPASS: java\nPASS: runelite\nPASS: x11",
            stderr = null, exitCode = 0, error = null
        ))
        val result = verifier.verify()
        assertTrue(result.success)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `proot failure is detected`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "FAIL: proot\nPASS: java\nPASS: runelite\nPASS: x11",
            stderr = null, exitCode = 0, error = null
        ))
        val result = verifier.verify()
        assertFalse(result.success)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].contains("proot"))
    }

    @Test
    fun `multiple failures detected`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "FAIL: proot\nFAIL: java\nPASS: runelite\nFAIL: x11",
            stderr = null, exitCode = 0, error = null
        ))
        val result = verifier.verify()
        assertFalse(result.success)
        assertEquals(3, result.failures.size)
    }

    @Test
    fun `non-zero exit with all PASS is success`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "PASS: proot\nPASS: java\nPASS: runelite\nPASS: x11",
            stderr = null, exitCode = 1, error = null
        ))
        val result = verifier.verify()
        assertTrue(result.success)
    }

    @Test
    fun `empty stdout is success`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))
        val result = verifier.verify()
        assertTrue(result.success)
    }

    @Test
    fun `correct command path is used`() = runTest {
        commandRunner.setDefaultResult(CommandResult(
            stdout = "PASS: proot\nPASS: java\nPASS: runelite\nPASS: x11",
            stderr = null, exitCode = 0, error = null
        ))
        verifier.verify()
        assertEquals(1, commandRunner.executedCommands.size)
        assertTrue(commandRunner.executedCommands[0].commandPath.endsWith("/bash"))
    }
}

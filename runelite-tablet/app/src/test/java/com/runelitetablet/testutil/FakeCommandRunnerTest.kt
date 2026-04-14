package com.runelitetablet.testutil

import com.runelitetablet.domain.command.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeCommandRunnerTest {

    @Test
    fun `records executed commands`() = runTest {
        val runner = FakeCommandRunner()
        runner.execute("/usr/bin/bash", arrayOf("-c", "echo hello"))
        assertEquals(1, runner.executedCommands.size)
        assertEquals("/usr/bin/bash", runner.executedCommands[0].commandPath)
    }

    @Test
    fun `returns configured response for command`() = runTest {
        val runner = FakeCommandRunner()
        val expected = CommandResult("output", null, 0, null)
        runner.whenCommand("/usr/bin/bash", expected)
        val result = runner.execute("/usr/bin/bash")
        assertEquals(expected, result)
    }

    @Test
    fun `returns default result for unconfigured command`() = runTest {
        val runner = FakeCommandRunner()
        val result = runner.execute("/unknown/cmd")
        assertEquals(0, result.exitCode)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `launch records command`() {
        val runner = FakeCommandRunner()
        val result = runner.launch("/usr/bin/bash", arrayOf("arg1"))
        assertTrue(result)
        assertEquals(1, runner.launchedCommands.size)
    }

    @Test
    fun `launchBackground records command`() {
        val runner = FakeCommandRunner()
        val result = runner.launchBackground("/usr/bin/bash")
        assertTrue(result)
        assertEquals(1, runner.launchedCommands.size)
    }

    @Test
    fun `custom default result applies`() = runTest {
        val runner = FakeCommandRunner()
        val custom = CommandResult(null, "error", 1, "failed")
        runner.setDefaultResult(custom)
        val result = runner.execute("/any/cmd")
        assertEquals(1, result.exitCode)
        assertFalse(result.isSuccess)
    }
}

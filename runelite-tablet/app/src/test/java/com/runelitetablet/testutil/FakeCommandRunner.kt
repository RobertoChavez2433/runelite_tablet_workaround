package com.runelitetablet.testutil

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.command.CommandRunner

/**
 * Android boundary fake — replaces TermuxCommandRunner.
 * Can't send Android Intents to Termux on a laptop.
 *
 * Records all commands for verification and returns configurable results.
 */
class FakeCommandRunner : CommandRunner {
    data class RecordedCommand(
        val commandPath: String,
        val arguments: Array<String>?,
        val workdir: String?,
        val background: Boolean,
        val stdin: String?,
        val timeoutMs: Long
    )

    val executedCommands = mutableListOf<RecordedCommand>()
    val launchedCommands = mutableListOf<RecordedCommand>()

    private val responses = mutableMapOf<String, CommandResult>()
    private var defaultResult = CommandResult(stdout = "", stderr = null, exitCode = 0, error = null)

    /** Set the result returned for a specific commandPath. */
    fun whenCommand(commandPath: String, result: CommandResult) {
        responses[commandPath] = result
    }

    /** Set the default result for any unregistered command. */
    fun setDefaultResult(result: CommandResult) {
        defaultResult = result
    }

    override suspend fun execute(
        commandPath: String,
        arguments: Array<String>?,
        workdir: String?,
        background: Boolean,
        stdin: String?,
        timeoutMs: Long
    ): CommandResult {
        executedCommands.add(RecordedCommand(commandPath, arguments, workdir, background, stdin, timeoutMs))
        return responses[commandPath] ?: defaultResult
    }

    override fun launch(commandPath: String, arguments: Array<String>?, sessionAction: String): Boolean {
        launchedCommands.add(RecordedCommand(commandPath, arguments, null, false, null, 0))
        return true
    }

    override fun launchBackground(commandPath: String, arguments: Array<String>?): Boolean {
        launchedCommands.add(RecordedCommand(commandPath, arguments, null, true, null, 0))
        return true
    }
}

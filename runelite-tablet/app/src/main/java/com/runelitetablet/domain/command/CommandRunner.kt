package com.runelitetablet.domain.command

/**
 * Abstraction for running shell commands. Pure Kotlin — no Android imports.
 * Production: TermuxCommandRunner (sends intents to Termux app).
 * Tests: FakeCommandRunner (returns scripted responses).
 */
interface CommandRunner {
    suspend fun execute(
        commandPath: String,
        arguments: Array<String>? = null,
        workdir: String? = null,
        background: Boolean = true,
        stdin: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    fun launch(
        commandPath: String,
        arguments: Array<String>? = null,
        sessionAction: String = SESSION_ACTION_SWITCH_NEW
    ): Boolean

    fun launchBackground(
        commandPath: String,
        arguments: Array<String>? = null
    ): Boolean

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20L * 60 * 1000 // 20 minutes
        const val SESSION_ACTION_SWITCH_NEW = "0"
        const val SESSION_ACTION_SWITCH_NEW_NO_ACTIVITY = "2"
        const val TIMEOUT_VERIFY_MS = 30L * 1000 // 30 seconds
        const val TERMUX_BIN_PATH = "/data/data/com.termux/files/usr/bin"
        const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
    }
}

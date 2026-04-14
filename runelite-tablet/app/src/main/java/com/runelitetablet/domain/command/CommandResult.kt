package com.runelitetablet.domain.command

/**
 * Result of a command execution. Pure Kotlin — no Android imports.
 * Mirrors the shape of TermuxResult for seamless transition.
 */
data class CommandResult(
    val stdout: String?,
    val stderr: String?,
    val exitCode: Int,
    val error: String?
) {
    val isSuccess: Boolean get() = exitCode == 0 && error == null
}

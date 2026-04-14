package com.runelitetablet.di

import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.termux.TermuxCommandRunner

/**
 * Session-related dependencies.
 * SessionHealthMonitor is created per-service lifecycle (in RuneLiteSessionService),
 * not at container creation — it requires a CoroutineScope tied to service lifecycle.
 */
class SessionModule(commandRunner: CommandRunner) {
    /** Expose the concrete TermuxCommandRunner for RuneLiteSessionService. */
    val termuxCommandRunner = commandRunner as TermuxCommandRunner
}

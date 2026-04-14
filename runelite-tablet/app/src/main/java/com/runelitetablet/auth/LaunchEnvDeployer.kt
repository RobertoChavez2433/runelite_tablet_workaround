package com.runelitetablet.auth

import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LaunchEnvDeployer {
    suspend fun deployToTermuxHome(
        credentialStore: CredentialStore,
        commandRunner: CommandRunner,
        logger: Logger? = AppLog,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): String? {
        val creds = withContext(ioDispatcher) { credentialStore.getCredentials() } ?: return null
        return try {
            val termuxEnvPath = "${CommandRunner.TERMUX_HOME_PATH}/.rlt-launch-env.sh"
            val content = buildString {
                appendLine("export JX_SESSION_ID=\"${shellEscape(creds.sessionId)}\"")
                appendLine("export JX_CHARACTER_ID=\"${shellEscape(creds.characterId)}\"")
                appendLine("export JX_DISPLAY_NAME=\"${shellEscape(creds.displayName)}\"")
            }
            val deployCommand = "cat > $termuxEnvPath && chmod 600 $termuxEnvPath"
            val result = commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", deployCommand),
                stdin = content,
                background = true,
                timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
            )
            if (result.isSuccess) {
                logger?.step("auth", "LaunchEnvDeployer: env file deployed to Termux at $termuxEnvPath (credentials masked)")
                termuxEnvPath
            } else {
                logger?.e(
                    "AUTH",
                    "LaunchEnvDeployer: failed to deploy env file: exitCode=${result.exitCode} error=${result.error}"
                )
                null
            }
        } catch (e: Exception) {
            logger?.e("AUTH", "LaunchEnvDeployer: failed to deploy env file: ${e.message}", e)
            null
        }
    }

    private fun shellEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")
}

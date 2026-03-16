package com.runelitetablet.auth

import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LaunchEnvDeployer {
    suspend fun deployToTermuxHome(
        credentialManager: CredentialManager,
        commandRunner: TermuxCommandRunner
    ): String? {
        val creds = withContext(Dispatchers.IO) { credentialManager.getCredentials() } ?: return null
        return try {
            val termuxEnvPath = "${TermuxCommandRunner.TERMUX_HOME_PATH}/.rlt-launch-env.sh"
            val content = buildString {
                appendLine("export JX_SESSION_ID=\"${shellEscape(creds.sessionId)}\"")
                appendLine("export JX_CHARACTER_ID=\"${shellEscape(creds.characterId)}\"")
                appendLine("export JX_DISPLAY_NAME=\"${shellEscape(creds.displayName)}\"")
            }
            val deployCommand = "cat > $termuxEnvPath && chmod 600 $termuxEnvPath"
            val result = commandRunner.execute(
                commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", deployCommand),
                stdin = content,
                background = true,
                timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
            )
            if (result.isSuccess) {
                AppLog.step("auth", "LaunchEnvDeployer: env file deployed to Termux at $termuxEnvPath (credentials masked)")
                termuxEnvPath
            } else {
                AppLog.e(
                    "AUTH",
                    "LaunchEnvDeployer: failed to deploy env file: exitCode=${result.exitCode} error=${result.error}"
                )
                null
            }
        } catch (e: Exception) {
            AppLog.e("AUTH", "LaunchEnvDeployer: failed to deploy env file: ${e.message}", e)
            null
        }
    }

    private fun shellEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")
}

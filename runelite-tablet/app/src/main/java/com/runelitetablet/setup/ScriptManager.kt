package com.runelitetablet.setup

import android.content.Context
import com.runelitetablet.termux.TermuxCommandRunner

class ScriptManager(
    private val context: Context,
    private val commandRunner: TermuxCommandRunner
) {

    companion object {
        private const val SCRIPTS_DIR = "${TermuxCommandRunner.TERMUX_HOME_PATH}/scripts"
        private val SCRIPT_NAMES = listOf("setup-environment.sh", "launch-runelite.sh")
    }

    suspend fun deployScripts(): Boolean {
        for (scriptName in SCRIPT_NAMES) {
            val success = deployScript(scriptName)
            if (!success) return false
        }
        return true
    }

    fun getScriptPath(name: String): String = "$SCRIPTS_DIR/$name"

    private suspend fun deployScript(scriptName: String): Boolean {
        val scriptContent = context.assets.open("scripts/$scriptName")
            .bufferedReader()
            .use { it.readText() }

        val deployCommand =
            "mkdir -p $SCRIPTS_DIR && cat > $SCRIPTS_DIR/$scriptName && chmod +x $SCRIPTS_DIR/$scriptName"

        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", deployCommand),
            stdin = scriptContent,
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
        )

        return result.isSuccess
    }
}

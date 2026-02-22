package com.runelitetablet.setup

import android.content.Context
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScriptManager(
    private val context: Context,
    private val commandRunner: TermuxCommandRunner
) {

    companion object {
        private const val SCRIPTS_DIR = "${TermuxCommandRunner.TERMUX_HOME_PATH}/scripts"
        private val SCRIPT_NAMES = listOf("setup-environment.sh", "launch-runelite.sh")
    }

    suspend fun deployScripts(): Boolean {
        AppLog.script("deployScripts: starting scriptCount=${SCRIPT_NAMES.size} scripts=${SCRIPT_NAMES}")
        for (scriptName in SCRIPT_NAMES) {
            val success = deployScript(scriptName)
            if (!success) {
                AppLog.script("deployScripts: halting — deployment failed for $scriptName")
                return false
            }
        }
        AppLog.script("deployScripts: all scripts deployed successfully")
        return true
    }

    fun getScriptPath(name: String): String = "$SCRIPTS_DIR/$name"

    private suspend fun deployScript(scriptName: String): Boolean {
        val assetReadStartMs = System.currentTimeMillis()
        val scriptContent = withContext(Dispatchers.IO) {
            context.assets.open("scripts/$scriptName").use {
                it.bufferedReader().readText()
            }
        }
        val assetReadDurationMs = System.currentTimeMillis() - assetReadStartMs
        AppLog.script(
            "deployScript: assetRead name=$scriptName contentLen=${scriptContent.length} " +
                "assetReadDurationMs=$assetReadDurationMs (Dispatchers.IO)"
        )

        val deployCommand =
            "mkdir -p $SCRIPTS_DIR && cat > $SCRIPTS_DIR/$scriptName && chmod +x $SCRIPTS_DIR/$scriptName"
        AppLog.script("deployScript: name=$scriptName contentLenBytes=${scriptContent.length} deployCommand='$deployCommand'")

        val deployStartMs = System.currentTimeMillis()
        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", deployCommand),
            stdin = scriptContent,
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
        )
        val deployDurationMs = System.currentTimeMillis() - deployStartMs

        return if (result.isSuccess) {
            AppLog.script("deployScript: success name=$scriptName durationMs=$deployDurationMs")
            true
        } else {
            AppLog.e(
                "SCRIPT",
                "deployScript: FAILED name=$scriptName durationMs=$deployDurationMs " +
                    "exitCode=${result.exitCode} error=${result.error} " +
                    "stdout=${result.stdout} stderr=${result.stderr}"
            )
            false
        }
    }
}

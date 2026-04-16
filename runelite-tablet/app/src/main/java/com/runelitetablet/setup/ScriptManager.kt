package com.runelitetablet.setup

import android.content.Context
import com.runelitetablet.domain.setup.ScriptDeployer
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScriptManager(
    private val context: Context,
    private val commandRunner: TermuxCommandRunner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ScriptDeployer {

    companion object {
        private const val SCRIPTS_DIR = "${TermuxCommandRunner.TERMUX_HOME_PATH}/scripts"
        private const val CONFIGS_DIR = "${TermuxCommandRunner.TERMUX_HOME_PATH}/scripts/configs"
        private val SCRIPT_NAMES = listOf(
            "install-proot.sh", "install-java.sh", "download-runelite.sh",
            "check-markers.sh", "check-x11-socket.sh", "launch-runelite.sh",
            "update-runelite.sh", "health-check.sh", "shutdown-session.sh",
            "detect-gpu.sh", "setup-gpu.sh", "setup-gpu-mali.sh",
            "monitor-cpu-affinity.sh", "benchmark-pipeline.sh",
            "profile-virgl-env.sh", "check-kernel-caps.sh",
            "test-server-zink.sh", "test-user-ns.sh"
        )
        private val CONFIG_NAMES = listOf("openbox-rc.xml")

        /** Process-wide cache so broadcast receivers don't re-deploy after activity setup did it. */
        @Volatile private var scriptsDeployedGlobal = false
        @Volatile private var configsDeployedGlobal = false
    }

    private var scriptsDeployed: Boolean
        get() = scriptsDeployedGlobal
        set(value) { scriptsDeployedGlobal = value }

    private var configsDeployed: Boolean
        get() = configsDeployedGlobal
        set(value) { configsDeployedGlobal = value }

    /** Cache script content after first APK asset read to avoid repeated decompression */
    private val scriptContentCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Reset deployment cache, e.g. after an error that may have left scripts in a bad state */
    override fun invalidateDeployCache() {
        scriptsDeployedGlobal = false
        configsDeployedGlobal = false
        scriptContentCache.clear()
        AppLog.script("invalidateDeployCache: deployment and content caches cleared")
    }

    override suspend fun deployScripts(): Boolean {
        if (scriptsDeployed) {
            AppLog.script("deployScripts: skipping — already deployed (cached)")
            return true
        }
        AppLog.script("deployScripts: starting scriptCount=${SCRIPT_NAMES.size} scripts=${SCRIPT_NAMES}")

        // Batch all scripts into a single Termux IPC call to avoid 12 sequential roundtrips
        // (~60ms each = ~850ms serial → ~80ms batched).
        val success = deployScriptsBatched(SCRIPT_NAMES)
        if (!success) {
            AppLog.script("deployScripts: batched deployment failed, falling back to serial")
            // Serial fallback
            for (scriptName in SCRIPT_NAMES) {
                if (!deployScript(scriptName)) {
                    AppLog.script("deployScripts: halting — deployment failed for $scriptName")
                    return false
                }
            }
        }

        AppLog.script("deployScripts: all scripts deployed successfully")
        scriptsDeployed = true
        return true
    }

    /**
     * Deploy all scripts in a single Termux IPC call. Builds a shell script that writes
     * each file using heredoc syntax, then chmod +x all at once.
     */
    private suspend fun deployScriptsBatched(scriptNames: List<String>): Boolean {
        val startMs = System.currentTimeMillis()
        // Read all scripts from assets
        val scripts = scriptNames.map { name ->
            val content = scriptContentCache.getOrPut(name) {
                withContext(ioDispatcher) {
                    context.assets.open("scripts/$name").use {
                        it.bufferedReader().readText().replace("\r", "")
                    }
                }
            }
            name to content
        }

        // Build a single shell command that writes all scripts
        val sb = StringBuilder()
        sb.append("mkdir -p $SCRIPTS_DIR && ")
        for ((name, content) in scripts) {
            // Use base64 to avoid heredoc delimiter collisions with script content
            val encoded = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            sb.append("echo '$encoded' | base64 -d > $SCRIPTS_DIR/$name && ")
        }
        sb.append("chmod +x")
        for ((name, _) in scripts) {
            sb.append(" $SCRIPTS_DIR/$name")
        }

        val totalBytes = scripts.sumOf { it.second.length }
        AppLog.script("deployScriptsBatched: ${scripts.size} scripts, ${totalBytes} bytes total")

        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", sb.toString()),
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
        )
        val durationMs = System.currentTimeMillis() - startMs

        return if (result.isSuccess) {
            AppLog.script("deployScriptsBatched: success durationMs=$durationMs")
            true
        } else {
            AppLog.w("SCRIPT", "deployScriptsBatched: failed exitCode=${result.exitCode} error=${result.error} durationMs=$durationMs")
            false
        }
    }

    override suspend fun deployConfigs(): Boolean {
        if (configsDeployed) {
            AppLog.script("deployConfigs: skipping — already deployed (cached)")
            return true
        }
        AppLog.script("deployConfigs: starting configCount=${CONFIG_NAMES.size} configs=${CONFIG_NAMES}")
        for (configName in CONFIG_NAMES) {
            val success = deployConfig(configName)
            if (!success) {
                AppLog.script("deployConfigs: halting — deployment failed for $configName")
                return false
            }
        }
        AppLog.script("deployConfigs: all configs deployed successfully")
        configsDeployed = true
        return true
    }

    override fun getScriptPath(name: String): String = "$SCRIPTS_DIR/$name"

    private suspend fun deployScript(scriptName: String): Boolean {
        val assetReadStartMs = System.currentTimeMillis()
        val scriptContent = scriptContentCache.getOrPut(scriptName) {
            withContext(ioDispatcher) {
                context.assets.open("scripts/$scriptName").use {
                    // Strip \r to ensure LF-only line endings — CRLF breaks shebang on Termux
                    it.bufferedReader().readText().replace("\r", "")
                }
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

    private suspend fun deployConfig(configName: String): Boolean {
        val assetReadStartMs = System.currentTimeMillis()
        val configContent = withContext(ioDispatcher) {
            context.assets.open("configs/$configName").use {
                it.bufferedReader().readText()
            }
        }
        val assetReadDurationMs = System.currentTimeMillis() - assetReadStartMs
        AppLog.script(
            "deployConfig: assetRead name=$configName contentLen=${configContent.length} " +
                "assetReadDurationMs=$assetReadDurationMs (Dispatchers.IO)"
        )

        // Configs are not executable — no chmod +x
        val deployCommand = "mkdir -p $CONFIGS_DIR && cat > $CONFIGS_DIR/$configName"
        AppLog.script("deployConfig: name=$configName contentLenBytes=${configContent.length} deployCommand='$deployCommand'")

        val deployStartMs = System.currentTimeMillis()
        val result = commandRunner.execute(
            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", deployCommand),
            stdin = configContent,
            background = true,
            timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
        )
        val deployDurationMs = System.currentTimeMillis() - deployStartMs

        return if (result.isSuccess) {
            AppLog.script("deployConfig: success name=$configName durationMs=$deployDurationMs")
            true
        } else {
            AppLog.e(
                "SCRIPT",
                "deployConfig: FAILED name=$configName durationMs=$deployDurationMs " +
                    "exitCode=${result.exitCode} error=${result.error} " +
                    "stdout=${result.stdout} stderr=${result.stderr}"
            )
            false
        }
    }
}

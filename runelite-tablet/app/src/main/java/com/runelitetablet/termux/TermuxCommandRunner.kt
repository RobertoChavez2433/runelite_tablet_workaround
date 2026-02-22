package com.runelitetablet.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.runelitetablet.PendingIntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class TermuxResult(
    val stdout: String?,
    val stderr: String?,
    val exitCode: Int,
    val error: String?
) {
    val isSuccess: Boolean get() = exitCode == 0 && error == null
}

class TermuxCommandRunner(private val context: Context) {

    companion object {
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val SESSION_ACTION_SWITCH_NEW = "0"
        const val SESSION_ACTION_SWITCH_NEW_NO_ACTIVITY = "2"

        const val TIMEOUT_SETUP_MS = 10L * 60 * 1000     // 10 minutes
        const val TIMEOUT_VERIFY_MS = 30L * 1000          // 30 seconds

        const val TERMUX_BIN_PATH = "/data/data/com.termux/files/usr/bin"
        const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
    }

    suspend fun execute(
        commandPath: String,
        arguments: Array<String>? = null,
        workdir: String? = null,
        background: Boolean = true,
        stdin: String? = null,
        timeoutMs: Long = TIMEOUT_SETUP_MS
    ): TermuxResult {
        val executionId = TermuxResultService.createExecutionId()
        val deferred = CompletableDeferred<TermuxResult>()
        TermuxResultService.pendingResults[executionId] = deferred

        val pendingIntent = createPendingIntent(executionId)

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(TermuxPackageHelper.TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            if (arguments != null) putExtra(EXTRA_ARGUMENTS, arguments)
            if (workdir != null) putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, background)
            if (stdin != null) putExtra(EXTRA_STDIN, stdin)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            context.startService(intent)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            TermuxResultService.pendingResults.remove(executionId)
            return TermuxResult(
                stdout = null,
                stderr = null,
                exitCode = -1,
                error = "Failed to start Termux service: ${e.message}"
            )
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            TermuxResultService.pendingResults.remove(executionId)
            TermuxResult(
                stdout = null,
                stderr = null,
                exitCode = -1,
                error = "Command timed out after ${timeoutMs}ms"
            )
        } catch (e: CancellationException) {
            TermuxResultService.pendingResults.remove(executionId)
            throw e
        } catch (e: Exception) {
            TermuxResultService.pendingResults.remove(executionId)
            TermuxResult(
                stdout = null,
                stderr = null,
                exitCode = -1,
                error = e.message ?: "Unknown error"
            )
        }
    }

    fun launch(
        commandPath: String,
        arguments: Array<String>? = null,
        sessionAction: String = SESSION_ACTION_SWITCH_NEW
    ): Boolean {
        return try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TermuxPackageHelper.TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, commandPath)
                if (arguments != null) putExtra(EXTRA_ARGUMENTS, arguments)
                putExtra(EXTRA_BACKGROUND, false)
                putExtra(EXTRA_SESSION_ACTION, sessionAction)
            }
            context.startService(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createPendingIntent(executionId: Int): PendingIntent {
        val intent = Intent(context, TermuxResultService::class.java).apply {
            putExtra("execution_id", executionId)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntentCompat.FLAGS
        return PendingIntent.getService(context, executionId, intent, flags)
    }
}

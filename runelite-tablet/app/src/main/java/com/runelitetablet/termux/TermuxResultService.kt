package com.runelitetablet.termux

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TermuxResultService : Service() {

    companion object {
        val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()

        private const val EXTRA_EXECUTION_ID = "execution_id"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERROR = "err"
        private const val EXTRA_ERROR_MSG = "errmsg"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private val counter = AtomicInteger(0)

        private const val STDOUT_PREVIEW_LEN = 200

        /** Patterns that may contain credentials in stdout/stderr output. */
        private val CREDENTIAL_PATTERNS = listOf(
            Regex("""(JX_SESSION_ID|JX_ACCESS_TOKEN|JX_REFRESH_TOKEN|JX_CHARACTER_ID)=[^\s]+"""),
            Regex("""Bearer\s+[A-Za-z0-9._-]+""", RegexOption.IGNORE_CASE)
        )

        fun createExecutionId(): Int = counter.incrementAndGet()

        /**
         * Scrub credential patterns from a string preview before logging.
         */
        private fun scrubCredentials(preview: String): String {
            var scrubbed = preview
            for (pattern in CREDENTIAL_PATTERNS) {
                scrubbed = pattern.replace(scrubbed, "[CREDENTIAL_REDACTED]")
            }
            return scrubbed
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.lifecycle("TermuxResultService.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            AppLog.cmd(-1, "onStartCommand: null intent received — pendingResults=${pendingResults.size} startId=$startId")
            stopSelfIfIdle(startId)
            return START_NOT_STICKY
        }

        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        val stdoutPreview = scrubCredentials(resultBundle?.getString(EXTRA_STDOUT)?.take(STDOUT_PREVIEW_LEN) ?: "<absent>")
        val stderrPreview = scrubCredentials(resultBundle?.getString(EXTRA_STDERR)?.take(STDOUT_PREVIEW_LEN) ?: "<absent>")
        val exitCode = resultBundle?.getInt(EXTRA_EXIT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val errCode = resultBundle?.getInt(EXTRA_ERROR, -1) ?: -1
        val errmsg = resultBundle?.getString(EXTRA_ERROR_MSG)
        AppLog.cmd(
            executionId,
            "onStartCommand: executionId=$executionId exitCode=$exitCode errCode=$errCode errmsg=$errmsg " +
                "stdoutPreview='$stdoutPreview' stderrPreview='$stderrPreview' " +
                "resultBundle=${resultBundle != null} pendingResultsSize=${pendingResults.size}"
        )

        handleResult(intent, startId)
        return START_NOT_STICKY
    }

    private fun handleResult(intent: Intent, startId: Int) {
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) {
            AppLog.e("CMD", "handleResult: missing execution_id in intent extras — dropping result")
            stopSelfIfIdle(startId)
            return
        }

        val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        val stdout = resultBundle?.getString(EXTRA_STDOUT)
        val stderr = resultBundle?.getString(EXTRA_STDERR)
        val exitCode = resultBundle?.getInt(EXTRA_EXIT_CODE, -1) ?: -1
        val errCode = resultBundle?.getInt(EXTRA_ERROR, -1) ?: -1
        val errmsg = resultBundle?.getString(EXTRA_ERROR_MSG)
        val error = errmsg ?: if (errCode > 0) "Termux error code: $errCode" else null

        val stdoutPreview = scrubCredentials(stdout?.take(STDOUT_PREVIEW_LEN) ?: "<null>")
        val stderrPreview = scrubCredentials(stderr?.take(STDOUT_PREVIEW_LEN) ?: "<null>")
        AppLog.cmd(
            executionId,
            "handleResult: executionId=$executionId exitCode=$exitCode errCode=$errCode errmsg=$errmsg " +
                "stdoutLen=${stdout?.length ?: 0} stdoutPreview='$stdoutPreview' " +
                "stderrLen=${stderr?.length ?: 0} stderrPreview='$stderrPreview'"
        )

        val result = TermuxResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            error = error
        )

        val deferred = pendingResults.remove(executionId)
        if (deferred != null) {
            AppLog.cmd(executionId, "handleResult: deferred found in pendingResults — completing mapSizeAfter=${pendingResults.size}")
            deferred.complete(result)
        } else {
            AppLog.cmd(executionId, "handleResult: deferred NOT found in pendingResults (already removed?) mapSize=${pendingResults.size}")
        }

        stopSelfIfIdle(startId)
    }

    private fun stopSelfIfIdle(startId: Int) {
        if (pendingResults.isEmpty()) {
            AppLog.lifecycle("TermuxResultService.stopSelfIfIdle: no pending results — stopSelf(startId=$startId)")
            stopSelf(startId)
        } else {
            AppLog.lifecycle("TermuxResultService.stopSelfIfIdle: ${pendingResults.size} pending result(s) — staying alive " +
                "pendingIds=${pendingResults.keys}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLog.lifecycle("TermuxResultService.onDestroy: pendingResults=${pendingResults.size} (not clearing — deferreds are static, outlive service instances)")
    }
}

package com.runelitetablet.termux

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
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
        private val counter = AtomicInteger(0)

        private const val STDOUT_PREVIEW_LEN = 200

        fun createExecutionId(): Int = counter.incrementAndGet()
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("TermuxResultHandler")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        AppLog.lifecycle("TermuxResultService.onCreate: HandlerThread started looper=${handlerThread.looper}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            AppLog.cmd(-1, "onStartCommand: null intent received — stopping self startId=$startId")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val stdoutPreview = intent.getStringExtra(EXTRA_STDOUT)?.take(STDOUT_PREVIEW_LEN) ?: "<absent>"
        val stderrPreview = intent.getStringExtra(EXTRA_STDERR)?.take(STDOUT_PREVIEW_LEN) ?: "<absent>"
        val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, Int.MIN_VALUE)
        val error = intent.getStringExtra(EXTRA_ERROR) ?: intent.getStringExtra(EXTRA_ERROR_MSG)
        AppLog.cmd(
            executionId,
            "onStartCommand: executionId=$executionId exitCode=$exitCode error=$error " +
                "stdoutPreview='$stdoutPreview' stderrPreview='$stderrPreview' " +
                "pendingResultsSize=${pendingResults.size}"
        )

        handler.post {
            handleResult(intent, startId)
        }

        return START_NOT_STICKY
    }

    private fun handleResult(intent: Intent, startId: Int) {
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) {
            AppLog.e("CMD", "handleResult: missing execution_id in intent extras — dropping result")
            stopSelf(startId)
            return
        }

        val stdout = intent.getStringExtra(EXTRA_STDOUT)
        val stderr = intent.getStringExtra(EXTRA_STDERR)
        val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, -1)
        val error = intent.getStringExtra(EXTRA_ERROR) ?: intent.getStringExtra(EXTRA_ERROR_MSG)

        val stdoutPreview = stdout?.take(STDOUT_PREVIEW_LEN) ?: "<null>"
        val stderrPreview = stderr?.take(STDOUT_PREVIEW_LEN) ?: "<null>"
        AppLog.cmd(
            executionId,
            "handleResult: executionId=$executionId exitCode=$exitCode error=$error " +
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

        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        val pendingCount = pendingResults.size
        AppLog.lifecycle("TermuxResultService.onDestroy: canceling $pendingCount pending result(s)")
        pendingResults.entries.forEach { (execId, deferred) ->
            AppLog.cmd(execId, "onDestroy: canceling pending deferred execId=$execId")
            deferred.completeExceptionally(IllegalStateException("Service destroyed"))
        }
        pendingResults.clear()
        handlerThread.quitSafely()
    }
}

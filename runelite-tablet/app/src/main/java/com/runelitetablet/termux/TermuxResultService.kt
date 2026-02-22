package com.runelitetablet.termux

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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

        fun createExecutionId(): Int = counter.incrementAndGet()
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("TermuxResultHandler")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        handler.post {
            handleResult(intent, startId)
        }

        return START_NOT_STICKY
    }

    private fun handleResult(intent: Intent, startId: Int) {
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) {
            stopSelf(startId)
            return
        }

        val result = TermuxResult(
            stdout = intent.getStringExtra(EXTRA_STDOUT),
            stderr = intent.getStringExtra(EXTRA_STDERR),
            exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, -1),
            error = intent.getStringExtra(EXTRA_ERROR) ?: intent.getStringExtra(EXTRA_ERROR_MSG)
        )

        pendingResults.remove(executionId)?.complete(result)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pendingResults.values.forEach {
            it.completeExceptionally(IllegalStateException("Service destroyed"))
        }
        pendingResults.clear()
        handlerThread.quitSafely()
    }
}

package com.runelitetablet.logging

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.runelitetablet.BuildConfig
import com.runelitetablet.domain.logging.Logger
import java.io.File

object AppLog : Logger {
    private const val TAG = "RLT"
    private const val MAX_STACK_FRAMES = 10

    private var fileWriter: LogFileWriter = LogFileWriter(null)
    private val startTime = SystemClock.elapsedRealtime()

    fun init(context: Context) {
        fileWriter.shutdown()
        val logsDir = File(context.filesDir, "logs")
        fileWriter = try {
            LogFileWriter.initForSession(logsDir)
        } catch (e: Exception) {
            Log.e(TAG, "AppLog.init failed: ${e.message}")
            LogFileWriter(null)
        }
    }

    override fun d(tag: String, message: String) = log(tag, message) { Log.d(TAG, it) }
    override fun i(tag: String, message: String) = log(tag, message) { Log.i(TAG, it) }
    override fun w(tag: String, message: String) = log(tag, message) { Log.w(TAG, it) }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        val line = formatLine(tag, message)
        if (throwable != null) {
            Log.e(TAG, line, throwable)
            val frames = throwable.stackTrace.take(MAX_STACK_FRAMES)
                .joinToString("\n") { "  at $it" }
            val extra = (throwable.stackTrace.size - frames.count { it == '\n' } - 1)
                .let { if (it > 0) "\n  ... $it more frames" else "" }
            fileWriter.write("$line\n$frames$extra")
        } else { Log.e(TAG, line); fileWriter.write(line) }
    }

    fun memorySnapshot() = PerfSnapshots.memorySnapshot()
    fun diskSnapshot(context: Context) = PerfSnapshots.diskSnapshot(context)
    fun perfSnapshot(context: Context) = PerfSnapshots.perfSnapshot(context)

    private inline fun log(tag: String, message: String, logcat: (String) -> Unit) {
        val line = formatLine(tag, message)
        logcat(line)
        if (BuildConfig.DEBUG) fileWriter.write(line)
    }

    private fun formatLine(prefix: String, message: String): String {
        val elapsed = SystemClock.elapsedRealtime() - startTime
        return "+${elapsed}ms [$prefix] $message | thread=${Thread.currentThread().name}"
    }
}

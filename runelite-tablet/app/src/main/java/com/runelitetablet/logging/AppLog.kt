package com.runelitetablet.logging

import android.content.Context
import android.os.HandlerThread
import android.os.Handler
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLog {

    private const val TAG = "RLT"
    private const val MAX_STACK_FRAMES = 10
    private const val LOGS_DIR = "logs"
    private const val MAX_LOG_FILES = 5
    private const val MAX_LOG_AGE_MS = 24L * 60L * 60L * 1000L // 24 hours

    private var fileWriter: LogFileWriter = LogFileWriter(null)
    private val startTime = SystemClock.elapsedRealtime()

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    fun init(context: Context) {
        fileWriter.shutdown()
        val logsDir = File(context.filesDir, LOGS_DIR)
        try {
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            rotateOldLogs(logsDir)
            val sessionFile = createSessionFile(logsDir)
            fileWriter = LogFileWriter(sessionFile)
        } catch (e: Exception) {
            // init must never crash the app — fall back to logcat-only mode
            Log.e(TAG, "AppLog.init failed, file logging disabled: ${e.message}")
            fileWriter = LogFileWriter(null)
        }
    }

    private fun rotateOldLogs(logsDir: File) {
        val now = System.currentTimeMillis()
        val logFiles = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?: return

        // Delete files older than 24h
        logFiles.forEach { file ->
            if (now - file.lastModified() > MAX_LOG_AGE_MS) {
                file.delete()
            }
        }

        // Keep only the 5 newest remaining files (delete extras)
        val remaining = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?: return
        if (remaining.size >= MAX_LOG_FILES) {
            remaining.sortedBy { it.lastModified() }
                .take(remaining.size - MAX_LOG_FILES + 1)
                .forEach { it.delete() }
        }
    }

    private fun createSessionFile(logsDir: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(logsDir, "rlt-session-$timestamp.log")
    }

    // -------------------------------------------------------------------------
    // Core log methods
    // -------------------------------------------------------------------------

    fun d(prefix: String, message: String) {
        val line = formatLine(prefix, message)
        Log.d(TAG, line)
        fileWriter.write(line)
    }

    fun i(prefix: String, message: String) {
        val line = formatLine(prefix, message)
        Log.i(TAG, line)
        fileWriter.write(line)
    }

    fun w(prefix: String, message: String) {
        val line = formatLine(prefix, message)
        Log.w(TAG, line)
        fileWriter.write(line)
    }

    fun e(prefix: String, message: String, throwable: Throwable? = null) {
        val line = formatLine(prefix, message)
        if (throwable != null) {
            Log.e(TAG, line, throwable)
            val fullLine = line + "\n" + formatStackTrace(throwable)
            fileWriter.write(fullLine)
        } else {
            Log.e(TAG, line)
            fileWriter.write(line)
        }
    }

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    fun lifecycle(message: String) = i("LIFECYCLE", message)

    fun step(stepId: String, message: String) = i("STEP", "[$stepId] $message")

    fun cmd(execId: Int, message: String) = i("CMD", "[id=$execId] $message")

    fun http(message: String) = i("HTTP", message)

    fun install(message: String) = i("INSTALL", message)

    fun cleanup(message: String) = i("CLEANUP", message)

    fun script(message: String) = i("SCRIPT", message)

    fun verify(message: String) = i("VERIFY", message)

    fun state(message: String) = i("STATE", message)

    fun ui(message: String) = i("UI", message)

    fun perf(message: String) = i("PERF", message)

    // -------------------------------------------------------------------------
    // Performance snapshots
    // -------------------------------------------------------------------------

    fun memorySnapshot(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val freeMb = rt.freeMemory() / 1_048_576L
        val maxMb = rt.maxMemory() / 1_048_576L
        return "heap: used=${usedMb}MB free=${freeMb}MB max=${maxMb}MB"
    }

    fun diskSnapshot(context: Context): String {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeMb = freeBytes / 1_048_576L
            val totalMb = totalBytes / 1_048_576L
            "disk: free=${freeMb}MB total=${totalMb}MB"
        } catch (e: Exception) {
            "disk: unavailable (${e.message})"
        }
    }

    fun perfSnapshot(context: Context): String {
        return "${memorySnapshot()} | ${diskSnapshot(context)}"
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private fun formatLine(prefix: String, message: String): String {
        val elapsed = SystemClock.elapsedRealtime() - startTime
        val thread = Thread.currentThread().name
        return "+${elapsed}ms [$prefix] $message | thread=$thread"
    }

    private fun formatStackTrace(throwable: Throwable): String {
        val frames = throwable.stackTrace
        val sb = StringBuilder()
        val frameCount = minOf(frames.size, MAX_STACK_FRAMES)
        for (i in 0 until frameCount) {
            sb.append("  at ${frames[i]}\n")
        }
        val remaining = frames.size - frameCount
        if (remaining > 0) {
            sb.append("  ... $remaining more frames")
        }
        return sb.toString().trimEnd()
    }

    // -------------------------------------------------------------------------
    // Inner class: LogFileWriter
    // -------------------------------------------------------------------------

    private class LogFileWriter(private val logFile: File?) {

        private val queue = ConcurrentLinkedQueue<String>()
        private val handlerThread: HandlerThread?
        private val handler: Handler?
        private var bufferedWriter: BufferedWriter?

        private val flushRunnable = object : Runnable {
            override fun run() {
                drainQueue()
                flushWriter()
                handler?.postDelayed(this, FLUSH_INTERVAL_MS)
            }
        }

        companion object {
            private const val FLUSH_INTERVAL_MS = 500L
            private const val BUFFER_SIZE = 8192
        }

        init {
            if (logFile != null) {
                handlerThread = HandlerThread("AppLog-Writer").also { it.start() }
                handler = Handler(handlerThread.looper)
                bufferedWriter = try {
                    BufferedWriter(FileWriter(logFile, true), BUFFER_SIZE)
                } catch (e: Exception) {
                    Log.e("RLT", "LogFileWriter: failed to open log file: ${e.message}")
                    null
                }
                handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
            } else {
                handlerThread = null
                handler = null
                bufferedWriter = null
            }
        }

        fun write(line: String) {
            if (handler == null) return
            queue.add(line)
            handler.post { drainQueue() }
        }

        fun shutdown() {
            handler?.removeCallbacks(flushRunnable)
            handler?.post {
                drainQueue()
                flushWriter()
                try {
                    bufferedWriter?.close()
                } catch (e: Exception) {
                    Log.e("RLT", "LogFileWriter: error closing writer: ${e.message}")
                }
                handlerThread?.quit()
            }
        }

        private fun drainQueue() {
            val writer = bufferedWriter ?: return
            try {
                var line = queue.poll()
                while (line != null) {
                    writer.write(line)
                    writer.newLine()
                    line = queue.poll()
                }
            } catch (e: Exception) {
                Log.e("RLT", "LogFileWriter: error writing to log file: ${e.message}")
                flushWriter()
            }
        }

        private fun flushWriter() {
            try {
                bufferedWriter?.flush()
            } catch (e: Exception) {
                Log.e("RLT", "LogFileWriter: error flushing log file: ${e.message}")
            }
        }
    }
}

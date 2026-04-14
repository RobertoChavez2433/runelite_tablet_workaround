package com.runelitetablet.logging

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Async file writer for AppLog. Queues log lines and writes them
 * on a background HandlerThread with periodic flushing.
 * Monitors queue health — warns if > 1000 pending entries.
 */
open class LogFileWriter(logFile: File?) {
    private val queue = ConcurrentLinkedQueue<String>()
    private val handlerThread: HandlerThread?
    private val handler: Handler?
    private var bufferedWriter: BufferedWriter?

    private val flushRunnable = object : Runnable {
        override fun run() {
            val queueSize = queue.size
            if (queueSize > QUEUE_WARN_THRESHOLD) {
                Log.w("RLT", "LogFileWriter: queue size=$queueSize exceeds threshold=$QUEUE_WARN_THRESHOLD")
            }
            if (handlerThread != null && !handlerThread.isAlive) {
                Log.e("RLT", "LogFileWriter: handler thread DEAD — file logging will stop")
                return
            }
            drainQueue()
            flushWriter()
            handler?.postDelayed(this, FLUSH_INTERVAL_MS)
        }
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
            try { bufferedWriter?.close() } catch (e: Exception) {
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
            Log.e("RLT", "LogFileWriter: error writing: ${e.message}")
            flushWriter()
        }
    }

    private fun flushWriter() {
        try { bufferedWriter?.flush() } catch (e: Exception) {
            Log.e("RLT", "LogFileWriter: error flushing: ${e.message}")
        }
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 500L
        private const val BUFFER_SIZE = 8192
        private const val MAX_LOG_FILES = 5
        private const val MAX_LOG_AGE_MS = 24L * 60L * 60L * 1000L
        private const val QUEUE_WARN_THRESHOLD = 1000

        fun initForSession(logsDir: File): LogFileWriter {
            if (!logsDir.exists()) logsDir.mkdirs()
            rotateOldLogs(logsDir)
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            return LogFileWriter(File(logsDir, "rlt-session-$timestamp.log"))
        }

        private fun rotateOldLogs(logsDir: File) {
            val now = System.currentTimeMillis()
            val logFiles = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return
            logFiles.forEach { if (now - it.lastModified() > MAX_LOG_AGE_MS) it.delete() }
            val remaining = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return
            if (remaining.size >= MAX_LOG_FILES) {
                remaining.sortedBy { it.lastModified() }
                    .take(remaining.size - MAX_LOG_FILES + 1)
                    .forEach { it.delete() }
            }
        }
    }
}

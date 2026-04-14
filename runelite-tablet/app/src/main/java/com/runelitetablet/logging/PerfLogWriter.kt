package com.runelitetablet.logging

import java.io.File

/**
 * Separate log writer for frame-rate/performance data.
 * Same async pattern as LogFileWriter, separate file (rlt-perf-*.log).
 * Prevents high-frequency frame logs (120/sec) from polluting general logs.
 */
class PerfLogWriter private constructor(logFile: File?) : LogFileWriter(logFile) {
    companion object {
        fun initForSession(logsDir: File): PerfLogWriter {
            if (!logsDir.exists()) logsDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            return PerfLogWriter(File(logsDir, "rlt-perf-$timestamp.log"))
        }
    }
}

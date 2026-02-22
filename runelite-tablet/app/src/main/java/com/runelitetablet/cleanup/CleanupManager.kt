package com.runelitetablet.cleanup

import android.content.Context
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CleanupManager(private val context: Context) {

    suspend fun cleanup() = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        AppLog.cleanup("Starting pre-setup cleanup")

        var totalFilesDeleted = 0
        var totalSessionsAbandoned = 0
        var totalBytesReclaimed = 0L

        try {
            val (filesDeleted, bytesReclaimed) = cleanCachedApks()
            totalFilesDeleted += filesDeleted
            totalBytesReclaimed += bytesReclaimed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.cleanup("cleanCachedApks failed: ${e.message}")
            AppLog.e("CLEANUP", "cleanCachedApks exception", e)
        }

        try {
            val sessionsAbandoned = cleanInstallerSessions()
            totalSessionsAbandoned += sessionsAbandoned
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.cleanup("cleanInstallerSessions failed: ${e.message}")
            AppLog.e("CLEANUP", "cleanInstallerSessions exception", e)
        }

        try {
            val (filesDeleted, bytesReclaimed) = cleanOldLogs()
            totalFilesDeleted += filesDeleted
            totalBytesReclaimed += bytesReclaimed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.cleanup("cleanOldLogs failed: ${e.message}")
            AppLog.e("CLEANUP", "cleanOldLogs exception", e)
        }

        val durationMs = System.currentTimeMillis() - startMs
        AppLog.cleanup(
            "Cleanup complete: filesDeleted=$totalFilesDeleted " +
                "sessionsAbandoned=$totalSessionsAbandoned " +
                "bytesReclaimed=$totalBytesReclaimed " +
                "durationMs=$durationMs"
        )
    }

    private fun cleanCachedApks(): Pair<Int, Long> {
        val apkDir = File(context.cacheDir, "apks")
        if (!apkDir.exists()) {
            AppLog.cleanup("cleanCachedApks: apk cache dir does not exist, skipping")
            return Pair(0, 0L)
        }

        val files = apkDir.listFiles() ?: run {
            AppLog.cleanup("cleanCachedApks: could not list apk cache dir, skipping")
            return Pair(0, 0L)
        }

        var filesDeleted = 0
        var bytesReclaimed = 0L

        for (file in files) {
            val sizeBytes = file.length()
            val ageMs = System.currentTimeMillis() - file.lastModified()
            AppLog.cleanup(
                "cleanCachedApks: examining ${file.name} " +
                    "size=$sizeBytes bytes age=${ageMs}ms"
            )

            if (sizeBytes == 0L) {
                try {
                    file.delete()
                    filesDeleted++
                    AppLog.cleanup(
                        "cleanCachedApks: deleted ${file.absolutePath} reason=zero_bytes"
                    )
                } catch (e: Exception) {
                    AppLog.cleanup("cleanCachedApks: failed to delete ${file.absolutePath}: ${e.message}")
                }
            } else {
                AppLog.cleanup("cleanCachedApks: keeping ${file.name} reason=non_empty")
            }
        }

        return Pair(filesDeleted, bytesReclaimed)
    }

    private fun cleanInstallerSessions(): Int {
        val packageInstaller = context.packageManager.packageInstaller
        val sessions = try {
            packageInstaller.mySessions
        } catch (e: Exception) {
            AppLog.cleanup("cleanInstallerSessions: failed to list sessions: ${e.message}")
            return 0
        }

        var sessionsAbandoned = 0

        for (session in sessions) {
            val sessionId = session.sessionId
            try {
                packageInstaller.abandonSession(sessionId)
                sessionsAbandoned++
                AppLog.cleanup("cleanInstallerSessions: abandoned sessionId=$sessionId")
            } catch (e: Exception) {
                AppLog.cleanup("cleanInstallerSessions: failed to abandon sessionId=$sessionId: ${e.message}")
            }
        }

        return sessionsAbandoned
    }

    private fun cleanOldLogs(): Pair<Int, Long> {
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) {
            AppLog.cleanup("cleanOldLogs: logs dir does not exist, skipping")
            return Pair(0, 0L)
        }

        val files = logsDir.listFiles { f -> f.isFile } ?: run {
            AppLog.cleanup("cleanOldLogs: could not list logs dir, skipping")
            return Pair(0, 0L)
        }

        // Sort descending by lastModified — newest first
        val sortedFiles = files.sortedByDescending { it.lastModified() }

        val toKeep = sortedFiles.take(5)
        val toDelete = sortedFiles.drop(5)

        var filesDeleted = 0
        var bytesReclaimed = 0L

        for (file in toDelete) {
            val sizeBytes = file.length()
            val ageMs = System.currentTimeMillis() - file.lastModified()
            try {
                file.delete()
                filesDeleted++
                bytesReclaimed += sizeBytes
                AppLog.cleanup(
                    "cleanOldLogs: deleted ${file.absolutePath} " +
                        "size=$sizeBytes bytes age=${ageMs}ms"
                )
            } catch (e: Exception) {
                AppLog.cleanup("cleanOldLogs: failed to delete ${file.absolutePath}: ${e.message}")
            }
        }

        AppLog.cleanup("cleanOldLogs: kept ${toKeep.size} log file(s), deleted $filesDeleted")

        return Pair(filesDeleted, bytesReclaimed)
    }
}

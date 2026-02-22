package com.runelitetablet.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.runelitetablet.PendingIntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

sealed class InstallResult {
    object Success : InstallResult()
    data class NeedsUserAction(val intent: Intent) : InstallResult()
    data class Failure(val message: String) : InstallResult()
}

class ApkInstaller(private val context: Context) {

    fun canInstallPackages(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    suspend fun install(apkFile: File): InstallResult {
        if (!apkFile.exists()) {
            return InstallResult.Failure("APK file not found: ${apkFile.absolutePath}")
        }

        val deferred = CompletableDeferred<InstallResult>()
        var sessionId = -1

        try {
            val packageInstaller = context.packageManager.packageInstaller

            // Clean up any abandoned sessions
            packageInstaller.mySessions.forEach { sessionInfo ->
                try {
                    packageInstaller.abandonSession(sessionInfo.sessionId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            InstallResultReceiver.pendingResults[sessionId] = deferred

            withContext(Dispatchers.IO) {
                session.use { s ->
                    s.openWrite("apk", 0, apkFile.length()).use { outputStream ->
                        apkFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                        s.fsync(outputStream)
                    }

                    val pendingIntent = createInstallIntent(sessionId)
                    s.commit(pendingIntent.intentSender)
                }
            }

            return try {
                withTimeout(120_000L) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                InstallResultReceiver.pendingResults.remove(sessionId)
                InstallResult.Failure("Installation timed out after 2 minutes")
            }
        } catch (e: CancellationException) {
            InstallResultReceiver.pendingResults.remove(sessionId)
            throw e
        } catch (e: IOException) {
            InstallResultReceiver.pendingResults.remove(sessionId)
            return InstallResult.Failure("Install failed: ${e.message}")
        } catch (e: SecurityException) {
            InstallResultReceiver.pendingResults.remove(sessionId)
            return InstallResult.Failure("Install permission denied: ${e.message}")
        }
    }

    private fun createInstallIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = "com.runelitetablet.INSTALL_RESULT"
            putExtra("session_id", sessionId)
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntentCompat.FLAGS)
    }
}

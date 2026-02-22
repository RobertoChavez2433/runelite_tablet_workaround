package com.runelitetablet.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
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
        InstallResultReceiver.pendingResult = deferred

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use {
                it.openWrite("apk", 0, apkFile.length()).use { outputStream ->
                    apkFile.inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                    it.fsync(outputStream)
                }

                val pendingIntent = createInstallIntent(sessionId)
                it.commit(pendingIntent.intentSender)
            }

            return deferred.await()
        } catch (e: IOException) {
            InstallResultReceiver.pendingResult = null
            return InstallResult.Failure("Install failed: ${e.message}")
        } catch (e: SecurityException) {
            InstallResultReceiver.pendingResult = null
            return InstallResult.Failure("Install permission denied: ${e.message}")
        }
    }

    private fun createInstallIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = "com.runelitetablet.INSTALL_RESULT"
            putExtra("session_id", sessionId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

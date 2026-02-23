package com.runelitetablet.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.runelitetablet.PendingIntentCompat
import com.runelitetablet.logging.AppLog
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

    companion object {
        /**
         * Callback invoked when PackageInstaller requires user confirmation (STATUS_PENDING_USER_ACTION).
         * Routes the confirmation dialog through an Activity context (required on Android 10+).
         * Set by SetupOrchestrator when actions are bound; null when unbound.
         */
        var onNeedsUserAction: ((Intent) -> Unit)? = null
    }

    fun canInstallPackages(): Boolean {
        val result = context.packageManager.canRequestPackageInstalls()
        AppLog.install("canInstallPackages: result=$result")
        return result
    }

    suspend fun install(apkFile: File, expectedPackageName: String? = null): InstallResult {
        if (!apkFile.exists()) {
            AppLog.install("install: APK file not found path=${apkFile.absolutePath}")
            return InstallResult.Failure("APK file not found: ${apkFile.absolutePath}")
        }

        // Verify APK package name matches expected before proceeding with install
        if (expectedPackageName != null) {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            val actualPackage = archiveInfo?.packageName
            if (actualPackage == null) {
                AppLog.e("INSTALL", "install: could not read package name from APK at ${apkFile.absolutePath}")
                return InstallResult.Failure("Could not verify APK package name")
            }
            if (actualPackage != expectedPackageName) {
                AppLog.e("INSTALL", "install: package name mismatch — expected=$expectedPackageName actual=$actualPackage")
                return InstallResult.Failure("APK package mismatch: expected $expectedPackageName, got $actualPackage")
            }
            AppLog.install("install: APK package name verified=$actualPackage")
        }

        val deferred = CompletableDeferred<InstallResult>()
        var sessionId = -1

        try {
            val packageInstaller = context.packageManager.packageInstaller

            // Clean up any abandoned sessions (guard avoids unnecessary Binder IPC on clean installs)
            val abandonedSessions = packageInstaller.mySessions
            if (abandonedSessions.isNotEmpty()) {
                AppLog.install(
                    "abandoned sessions cleanup: count=${abandonedSessions.size} " +
                        "sessionIds=${abandonedSessions.map { it.sessionId }}"
                )
                abandonedSessions.forEach { sessionInfo ->
                    try {
                        packageInstaller.abandonSession(sessionInfo.sessionId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
            }

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            sessionId = packageInstaller.createSession(params)
            AppLog.install(
                "session create: sessionId=$sessionId mode=MODE_FULL_INSTALL " +
                    "apkPath=${apkFile.absolutePath} apkSizeBytes=${apkFile.length()}"
            )
            val session = packageInstaller.openSession(sessionId)

            InstallResultReceiver.pendingResults[sessionId] = deferred

            val writeStartMs = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                session.use { s ->
                    s.openWrite("apk", 0, apkFile.length()).use { outputStream ->
                        apkFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                        val syncStartMs = System.currentTimeMillis()
                        s.fsync(outputStream)
                        val fsyncDurationMs = System.currentTimeMillis() - syncStartMs
                        val writeDurationMs = System.currentTimeMillis() - writeStartMs
                        AppLog.install(
                            "session write: sessionId=$sessionId bytesWritten=${apkFile.length()} " +
                                "fsyncDurationMs=$fsyncDurationMs totalWriteDurationMs=$writeDurationMs"
                        )
                    }

                    val pendingIntent = createInstallIntent(sessionId)
                    AppLog.install(
                        "session commit: sessionId=$sessionId " +
                            "pendingIntentAction=com.runelitetablet.INSTALL_RESULT requestCode=$sessionId"
                    )
                    s.commit(pendingIntent.intentSender)
                }
            }

            return try {
                val timeoutMs = 120_000L
                AppLog.install("install: awaiting result sessionId=$sessionId timeoutMs=$timeoutMs")
                withTimeout(timeoutMs) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.install("install: timeout waitedMs=120000 sessionId=$sessionId")
                InstallResultReceiver.pendingResults.remove(sessionId)
                if (sessionId != -1) try { context.packageManager.packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
                InstallResult.Failure("Installation timed out after 2 minutes")
            }
        } catch (e: CancellationException) {
            InstallResultReceiver.pendingResults.remove(sessionId)
            if (sessionId != -1) try { context.packageManager.packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
            throw e
        } catch (e: IOException) {
            AppLog.e("INSTALL", "install: IOException sessionId=$sessionId message=${e.message}", e)
            InstallResultReceiver.pendingResults.remove(sessionId)
            if (sessionId != -1) try { context.packageManager.packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
            return InstallResult.Failure("Install failed: ${e.message}")
        } catch (e: SecurityException) {
            AppLog.e("INSTALL", "install: SecurityException sessionId=$sessionId message=${e.message}", e)
            InstallResultReceiver.pendingResults.remove(sessionId)
            if (sessionId != -1) try { context.packageManager.packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
            return InstallResult.Failure("Install permission denied: ${e.message}")
        }
    }

    private fun createInstallIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = "com.runelitetablet.INSTALL_RESULT"
            putExtra("session_id", sessionId)
        }
        // FLAG_ONE_SHOT prevents the PendingIntent from being reused after delivery
        return PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntentCompat.FLAGS or PendingIntent.FLAG_ONE_SHOT
        )
    }
}

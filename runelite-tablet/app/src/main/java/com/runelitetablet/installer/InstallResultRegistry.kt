package com.runelitetablet.installer

import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds pending APK install results. Shared between ApkInstaller (producer)
 * and InstallResultReceiver (consumer). Lives in AppContainer — no more global static.
 */
class InstallResultRegistry {
    val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()

    fun register(sessionId: Int): CompletableDeferred<InstallResult> {
        val deferred = CompletableDeferred<InstallResult>()
        pendingResults[sessionId] = deferred
        AppLog.d("INSTALL", "InstallResultRegistry: registered sessionId=$sessionId pending=${pendingResults.size}")
        return deferred
    }

    fun complete(sessionId: Int, result: InstallResult) {
        val deferred = pendingResults.remove(sessionId)
        if (deferred != null) {
            AppLog.d("INSTALL", "InstallResultRegistry: completed sessionId=$sessionId result=$result pending=${pendingResults.size}")
            deferred.complete(result)
        } else {
            AppLog.w("INSTALL", "InstallResultRegistry: orphan result sessionId=$sessionId (no waiting deferred)")
        }
    }
}

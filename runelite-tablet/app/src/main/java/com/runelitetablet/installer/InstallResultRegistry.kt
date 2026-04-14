package com.runelitetablet.installer

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds pending APK install results. Shared between ApkInstaller (producer)
 * and InstallResultReceiver (consumer). Lives in AppContainer — no more global static.
 */
class InstallResultRegistry {
    val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()
}

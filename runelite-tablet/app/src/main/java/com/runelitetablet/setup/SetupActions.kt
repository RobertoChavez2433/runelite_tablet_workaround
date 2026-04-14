package com.runelitetablet.setup

import android.content.Intent

/**
 * Callback interface to decouple SetupOrchestrator from Activity.
 * Avoids holding a direct Activity reference (leak risk).
 */
interface SetupActions {
    fun requestInstallPermission()
    fun requestTermuxPermission()
    fun requestNotificationPermission()
    fun requestBatteryOptimization(packageName: String)
    fun openAppSettings(packageName: String)
    fun launchIntent(intent: Intent)
    fun launchAuthActivity(intent: Intent)
}

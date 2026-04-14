package com.runelitetablet.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.runelitetablet.domain.command.CommandRunner

/**
 * Handles permission checks and battery optimization logic.
 * Extracted from SetupOrchestrator for ≤200 LoC compliance.
 */
open class PermissionHandler(
    private val context: Context,
    private val commandRunner: CommandRunner
) : com.runelitetablet.domain.setup.PermissionChecker {
    override suspend fun verifyPermissions(): Boolean {
        return try {
            val result = commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/echo",
                arguments = arrayOf("ok"),
                background = true,
                timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
            )
            result.isSuccess && result.stdout?.trim() == "ok"
        } catch (e: SecurityException) { false }
    }

    override fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    open fun isBatteryOptimized(packageName: String): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun hasRequiredBatteryExemptions(): Boolean = getNextBatteryOptimizationPackage() == null

    override fun getNextBatteryOptimizationPackage(): String? {
        return when {
            !isBatteryOptimized(TERMUX_PACKAGE) -> TERMUX_PACKAGE
            !isBatteryOptimized(TERMUX_X11_PACKAGE) -> TERMUX_X11_PACKAGE
            else -> null
        }
    }

    open fun requestBatteryOptimization(actions: SetupActions?) {
        val packageName = getNextBatteryOptimizationPackage() ?: TERMUX_PACKAGE
        try {
            if (packageName == TERMUX_X11_PACKAGE) actions?.openAppSettings(packageName)
            else actions?.requestBatteryOptimization(packageName)
        } catch (e: Exception) {
            actions?.openAppSettings(packageName)
        }
    }

    open fun requestOwnBatteryOptimization(actions: SetupActions?) {
        if (!isBatteryOptimized(context.packageName)) {
            try { actions?.requestBatteryOptimization(context.packageName) }
            catch (_: Exception) {}
        }
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"
    }
}

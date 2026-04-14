package com.runelitetablet.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.logging.AppLog

/**
 * Handles permission checks and battery optimization logic.
 * Extracted from SetupOrchestrator for ≤200 LoC compliance.
 */
open class PermissionHandler(
    private val context: Context,
    private val commandRunner: CommandRunner,
    private val logger: Logger = AppLog
) : com.runelitetablet.domain.setup.PermissionChecker {
    override suspend fun verifyPermissions(): Boolean {
        logger.state("PermissionHandler.verifyPermissions: permission=RUN_COMMAND checking")
        return try {
            val result = commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/echo",
                arguments = arrayOf("ok"),
                background = true,
                timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
            )
            val verified = result.isSuccess && result.stdout?.trim() == "ok"
            logger.state("PermissionHandler.verifyPermissions: user_response=${if (verified) "granted" else "denied"} exitCode=${result.exitCode}")
            verified
        } catch (e: SecurityException) {
            logger.w("PERM", "verifyPermissions: permission=RUN_COMMAND user_response=denied (SecurityException: ${e.message})")
            false
        }
    }

    override fun hasNotificationPermission(): Boolean {
        logger.state("PermissionHandler.hasNotificationPermission: permission=POST_NOTIFICATIONS checking")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            logger.state("PermissionHandler.hasNotificationPermission: permission=POST_NOTIFICATIONS user_response=${if (granted) "granted" else "denied"}")
            granted
        } else {
            logger.state("PermissionHandler.hasNotificationPermission: permission=POST_NOTIFICATIONS rationale=not_required (API < TIRAMISU)")
            true
        }
    }

    open fun isBatteryOptimized(packageName: String): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        val optimized = pm.isIgnoringBatteryOptimizations(packageName)
        logger.state("PermissionHandler.isBatteryOptimized: permission=BATTERY_OPTIMIZATION package=$packageName result=$optimized")
        return optimized
    }

    override fun hasRequiredBatteryExemptions(): Boolean {
        val result = getNextBatteryOptimizationPackage() == null
        logger.state("PermissionHandler.hasRequiredBatteryExemptions: result=$result")
        return result
    }

    override fun getNextBatteryOptimizationPackage(): String? {
        return when {
            !isBatteryOptimized(TERMUX_PACKAGE) -> TERMUX_PACKAGE
            !isBatteryOptimized(TERMUX_X11_PACKAGE) -> TERMUX_X11_PACKAGE
            else -> null
        }
    }

    open fun requestBatteryOptimization(actions: SetupActions?) {
        val packageName = getNextBatteryOptimizationPackage() ?: TERMUX_PACKAGE
        logger.state("PermissionHandler.requestBatteryOptimization: permission=BATTERY_OPTIMIZATION package=$packageName requesting")
        try {
            if (packageName == TERMUX_X11_PACKAGE) {
                logger.state("PermissionHandler.requestBatteryOptimization: rationale=x11_requires_app_settings follow_up=openAppSettings")
                actions?.openAppSettings(packageName)
            } else {
                logger.state("PermissionHandler.requestBatteryOptimization: follow_up=requestBatteryOptimization")
                actions?.requestBatteryOptimization(packageName)
            }
        } catch (e: Exception) {
            logger.w("PERM", "requestBatteryOptimization: failed for $packageName, follow_up=openAppSettings fallback (${e.message})")
            actions?.openAppSettings(packageName)
        }
    }

    open fun requestOwnBatteryOptimization(actions: SetupActions?) {
        val ownPackage = context.packageName
        logger.state("PermissionHandler.requestOwnBatteryOptimization: permission=BATTERY_OPTIMIZATION package=$ownPackage checking")
        if (!isBatteryOptimized(ownPackage)) {
            logger.state("PermissionHandler.requestOwnBatteryOptimization: follow_up=requestBatteryOptimization package=$ownPackage")
            try { actions?.requestBatteryOptimization(ownPackage) }
            catch (e: Exception) {
                logger.w("PERM", "requestOwnBatteryOptimization: failed for $ownPackage (${e.message})")
            }
        } else {
            logger.state("PermissionHandler.requestOwnBatteryOptimization: already_optimized package=$ownPackage")
        }
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"
    }
}

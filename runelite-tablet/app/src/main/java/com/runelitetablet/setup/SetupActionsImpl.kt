package com.runelitetablet.setup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.logging.AppLog

/**
 * Concrete SetupActions backed by a live Activity.
 * Created in SetupViewModel.bindActions(), nulled in unbindActions().
 */
class SetupActionsImpl(
    private val activity: Activity,
    private val authLauncher: ActivityResultLauncher<Intent>,
    private val logger: Logger = AppLog
) : SetupActions {

    override fun requestInstallPermission() {
        logger.ui("SetupActionsImpl: action=requestInstallPermission received")
        logger.ui("SetupActionsImpl: delegating to Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES")
        activity.startActivity(Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        ))
        logger.ui("SetupActionsImpl: requestInstallPermission delegation result=started")
    }

    override fun requestTermuxPermission() {
        logger.ui("SetupActionsImpl: action=requestTermuxPermission received")
        val permission = "com.termux.permission.RUN_COMMAND"
        if (activity.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            logger.ui("SetupActionsImpl: delegating to Activity.requestPermissions for RUN_COMMAND")
            activity.requestPermissions(arrayOf(permission), SetupViewModel.REQUEST_TERMUX_PERMISSION)
            logger.ui("SetupActionsImpl: requestTermuxPermission delegation result=requested")
        } else {
            logger.ui("SetupActionsImpl: requestTermuxPermission delegation result=already_granted")
        }
    }

    override fun requestNotificationPermission() {
        logger.ui("SetupActionsImpl: action=requestNotificationPermission received")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                logger.ui("SetupActionsImpl: delegating to Activity.requestPermissions for POST_NOTIFICATIONS")
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    SetupViewModel.REQUEST_NOTIFICATION_PERMISSION
                )
                logger.ui("SetupActionsImpl: requestNotificationPermission delegation result=requested")
            } else {
                logger.ui("SetupActionsImpl: requestNotificationPermission delegation result=already_granted")
            }
        } else {
            logger.ui("SetupActionsImpl: requestNotificationPermission delegation result=not_required (API < TIRAMISU)")
        }
    }

    override fun requestBatteryOptimization(packageName: String) {
        logger.ui("SetupActionsImpl: action=requestBatteryOptimization received package=$packageName")
        logger.ui("SetupActionsImpl: delegating to Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        activity.startActivity(Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        ))
        logger.ui("SetupActionsImpl: requestBatteryOptimization delegation result=started")
    }

    override fun openAppSettings(packageName: String) {
        logger.ui("SetupActionsImpl: action=openAppSettings received package=$packageName")
        logger.ui("SetupActionsImpl: delegating to Settings.ACTION_APPLICATION_DETAILS_SETTINGS")
        activity.startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ))
        logger.ui("SetupActionsImpl: openAppSettings delegation result=started")
    }

    override fun launchIntent(intent: Intent) {
        logger.ui("SetupActionsImpl: action=launchIntent received intent=${intent.action ?: intent.component}")
        logger.ui("SetupActionsImpl: delegating to Activity.startActivity")
        activity.startActivity(intent)
        logger.ui("SetupActionsImpl: launchIntent delegation result=started")
    }

    override fun launchAuthActivity(intent: Intent) {
        logger.ui("SetupActionsImpl: action=launchAuthActivity received")
        logger.ui("SetupActionsImpl: delegating to authLauncher.launch")
        authLauncher.launch(intent)
        logger.ui("SetupActionsImpl: launchAuthActivity delegation result=launched")
    }
}

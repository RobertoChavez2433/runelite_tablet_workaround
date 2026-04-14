package com.runelitetablet.setup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

/**
 * Concrete SetupActions backed by a live Activity.
 * Created in SetupViewModel.bindActions(), nulled in unbindActions().
 */
class SetupActionsImpl(
    private val activity: Activity,
    private val authLauncher: ActivityResultLauncher<Intent>
) : SetupActions {

    override fun requestInstallPermission() {
        activity.startActivity(Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        ))
    }

    override fun requestTermuxPermission() {
        val permission = "com.termux.permission.RUN_COMMAND"
        if (activity.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(permission), SetupViewModel.REQUEST_TERMUX_PERMISSION)
        }
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    SetupViewModel.REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun requestBatteryOptimization(packageName: String) {
        activity.startActivity(Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        ))
    }

    override fun openAppSettings(packageName: String) {
        activity.startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ))
    }

    override fun launchIntent(intent: Intent) { activity.startActivity(intent) }
    override fun launchAuthActivity(intent: Intent) { authLauncher.launch(intent) }
}

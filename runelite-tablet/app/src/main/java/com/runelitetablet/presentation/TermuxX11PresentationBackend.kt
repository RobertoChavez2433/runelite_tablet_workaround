package com.runelitetablet.presentation

import android.content.Context
import android.content.Intent
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.logging.AppLog
import com.runelitetablet.ui.DisplayPreferences

object TermuxX11PresentationBackend : PresentationBackend {
    private const val ACTION_CHANGE_PREFERENCE = "com.termux.x11.CHANGE_PREFERENCE"

    override val id: String = "termux_x11"
    override val displayName: String = "Termux:X11"

    override fun isInstalled(packageChecker: PackageChecker): Boolean =
        packageChecker.isTermuxX11Installed()

    override fun applyLaunchPreferences(context: Context, displayPreferences: DisplayPreferences) {
        val prefIntent = Intent(ACTION_CHANGE_PREFERENCE).apply {
            setPackage(PackageChecker.TERMUX_X11_PACKAGE)
            putExtra("fullscreen", displayPreferences.fullscreen.toString())
            putExtra("showAdditionalKbd", displayPreferences.showKeyboardBar.toString())
            putExtra("displayResolutionMode", displayPreferences.resolutionMode)
            if (displayPreferences.resolutionMode == "exact") {
                putExtra("displayResolutionExactX", displayPreferences.customWidth.toString())
                putExtra("displayResolutionExactY", displayPreferences.customHeight.toString())
            }
        }
        context.sendBroadcast(prefIntent)
        AppLog.step(
            "launch",
            "presentation: sent CHANGE_PREFERENCE broadcast to $displayName " +
                "(mode=${displayPreferences.resolutionMode} fullscreen=${displayPreferences.fullscreen})"
        )
    }

    override fun createLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PackageChecker.TERMUX_X11_PACKAGE)

    override fun shouldWaitForReadySignal(): Boolean = true
}

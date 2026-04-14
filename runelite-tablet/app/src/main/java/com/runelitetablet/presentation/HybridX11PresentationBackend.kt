package com.runelitetablet.presentation

import android.content.Context
import android.content.Intent
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.presentation.hybrid.HybridX11HostActivity
import com.runelitetablet.ui.DisplayPreferences

/**
 * Option C backend: stock Termux-side X server, but the visible Android
 * surface is hosted by our own app activity.
 */
object HybridX11PresentationBackend : PresentationBackend {
    override val id: String = "hybrid_x11"
    override val displayName: String = "Hybrid X11"

    override fun isInstalled(packageChecker: PackageChecker): Boolean =
        packageChecker.isTermuxX11Installed()

    override fun applyLaunchPreferences(context: Context, displayPreferences: DisplayPreferences) {
        TermuxX11PresentationBackend.applyLaunchPreferences(context, displayPreferences)
    }

    override fun createLaunchIntent(context: Context): Intent =
        Intent(context, HybridX11HostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    override fun shouldForegroundBeforeBootstrap(): Boolean = false
}

package com.runelitetablet.presentation

import android.content.Context
import android.content.Intent
import com.runelitetablet.termux.TermuxPackageHelper
import com.runelitetablet.ui.DisplayPreferences

/**
 * Encapsulates how the app prepares and foregrounds the visible game surface.
 *
 * The stable backend today is Termux:X11. Experimental backends can replace the
 * presentation owner without forcing launch/session code to hardcode package names.
 */
interface PresentationBackend {
    val id: String
    val displayName: String

    fun isInstalled(termuxHelper: TermuxPackageHelper): Boolean

    fun applyLaunchPreferences(context: Context, displayPreferences: DisplayPreferences)

    fun createLaunchIntent(context: Context): Intent?

    fun createSwitchIntent(context: Context): Intent? =
        createLaunchIntent(context)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

    fun shouldWaitForReadySignal(): Boolean = false
}

package com.runelitetablet.presentation

import android.content.Context
import android.content.Intent
import com.runelitetablet.ui.DisplayPreferences
import com.runelitetablet.termux.TermuxPackageHelper

/**
 * Experimental backend for this branch. It does not launch RuneLite yet; it
 * only gives us an app-owned surface host to validate refresh, pacing, and
 * surface lifecycle independent of Termux:X11.
 */
object DirectSurfaceProbeBackend : PresentationBackend {
    override val id: String = "direct_surface_probe"
    override val displayName: String = "Direct Android Surface Probe"

    override fun isInstalled(termuxHelper: TermuxPackageHelper): Boolean = true

    override fun applyLaunchPreferences(context: Context, displayPreferences: DisplayPreferences) = Unit

    override fun createLaunchIntent(context: Context): Intent =
        Intent(context, DirectSurfaceProbeActivity::class.java)
}

package com.runelitetablet.ui

import android.content.Context

/**
 * Persistent display preferences backed by SharedPreferences.
 * Controls Termux:X11 display mode, resolution, fullscreen, and keyboard bar settings.
 * These preferences are sent to Termux:X11 via CHANGE_PREFERENCE broadcast before launch.
 */
class DisplayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("display_prefs", Context.MODE_PRIVATE)

    /**
     * Resolution mode for Termux:X11.
     * Values: "native" (device resolution), "scaled" (scaled), "exact" (custom resolution).
     */
    var resolutionMode: String
        get() = prefs.getString("resolution_mode", "native") ?: "native"
        set(value) = prefs.edit().putString("resolution_mode", value).apply()

    /** Custom width in pixels (used only when resolutionMode == "exact"). */
    var customWidth: Int
        get() = prefs.getInt("custom_width", 2960)
        set(value) = prefs.edit().putInt("custom_width", value).apply()

    /** Custom height in pixels (used only when resolutionMode == "exact"). */
    var customHeight: Int
        get() = prefs.getInt("custom_height", 1848)
        set(value) = prefs.edit().putInt("custom_height", value).apply()

    /** Whether Termux:X11 should run in fullscreen mode. */
    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", true)
        set(value) = prefs.edit().putBoolean("fullscreen", value).apply()

    /** Whether to show the additional keyboard bar in Termux:X11. */
    var showKeyboardBar: Boolean
        get() = prefs.getBoolean("show_keyboard_bar", false)
        set(value) = prefs.edit().putBoolean("show_keyboard_bar", value).apply()
}

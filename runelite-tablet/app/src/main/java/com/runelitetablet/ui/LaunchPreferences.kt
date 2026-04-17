package com.runelitetablet.ui

import android.content.Context

/**
 * Persistent launch preferences backed by SharedPreferences.
 * Controls which RuneLite launch path is used (proot vs native-Termux).
 * Mirrors the DisplayPreferences pattern.
 */
class LaunchPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("launch_prefs", Context.MODE_PRIVATE)

    /**
     * When true, launches RuneLite directly under Termux-native openjdk-21 via
     * `launch-runelite-native.sh`, bypassing proot-distro entirely. Requires the
     * Bionic rlawt jar to have been deployed to `$HOME/.rlt/rlawt-1.8-bionic.jar`
     * by ScriptManager.deployJars().
     *
     * Default: false (the proven proot path). Opt in explicitly to test the
     * native path; flip back to false if the native path regresses.
     */
    var useNativeTermux: Boolean
        get() = prefs.getBoolean("use_native_termux", false)
        set(value) = prefs.edit().putBoolean("use_native_termux", value).apply()
}

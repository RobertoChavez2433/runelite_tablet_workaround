package com.runelitetablet.setup

import android.content.Context

/**
 * Persistent setup state backed by SharedPreferences (UI cache layer).
 * Ground truth is marker files in Termux filesystem (~/.runelite-tablet/markers/).
 * This cache enables instant UI rendering on app start before marker reconciliation.
 *
 * Step keys: "step-proot", "step-java", "step-runelite"
 * Termux/X11 install steps use package-presence checks (existing pattern).
 * EnablePermissions uses the Termux echo test (existing pattern).
 */
class SetupStateStore(private val context: Context) {
    companion object {
        /** Must match SCRIPT_VERSION written by shell scripts. Bump on APK updates with changed scripts. */
        const val CURRENT_SCRIPT_VERSION = "2"
    }

    private val prefs = context.getSharedPreferences("setup_state", Context.MODE_PRIVATE)

    fun markCompleted(key: String) = prefs.edit().putBoolean(key, true).apply()

    fun isCompleted(key: String): Boolean = prefs.getBoolean(key, false)

    fun getStoredVersion(): String = prefs.getString("script_version", "0") ?: "0"

    fun setStoredVersion(v: String) = prefs.edit().putString("script_version", v).apply()

    fun isVersionCurrent(): Boolean = getStoredVersion() == CURRENT_SCRIPT_VERSION

    fun clearAll() = prefs.edit().clear().apply()
}

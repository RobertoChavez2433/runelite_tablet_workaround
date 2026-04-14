package com.runelitetablet.setup

import android.content.Context
import com.runelitetablet.domain.setup.SetupStateStore

/**
 * Persistent setup state backed by SharedPreferences (UI cache layer).
 * Ground truth is marker files in Termux filesystem (~/.runelite-tablet/markers/).
 * This cache enables instant UI rendering on app start before marker reconciliation.
 */
class SharedPrefsSetupStateStore(context: Context) : SetupStateStore {

    private val prefs = context.getSharedPreferences("setup_state", Context.MODE_PRIVATE)

    override fun markCompleted(key: String) = prefs.edit().putBoolean(key, true).apply()

    override fun clearCompleted(key: String) = prefs.edit().remove(key).apply()

    override fun isCompleted(key: String): Boolean = prefs.getBoolean(key, false)

    override fun getStoredVersion(): String = prefs.getString("script_version", "0") ?: "0"

    override fun setStoredVersion(v: String) = prefs.edit().putString("script_version", v).apply()

    override fun isVersionCurrent(): Boolean = getStoredVersion() == SetupStateStore.CURRENT_SCRIPT_VERSION

    override fun clearAll() = prefs.edit().clear().apply()
}

package com.runelitetablet.setup

import android.content.Context
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.logging.AppLog

/**
 * Persistent setup state backed by SharedPreferences (UI cache layer).
 * Ground truth is marker files in Termux filesystem (~/.runelite-tablet/markers/).
 * This cache enables instant UI rendering on app start before marker reconciliation.
 */
class SharedPrefsSetupStateStore(
    context: Context,
    private val logger: Logger = AppLog
) : SetupStateStore {

    private val prefs = context.getSharedPreferences("setup_state", Context.MODE_PRIVATE)

    override fun markCompleted(key: String) {
        logger.d("STATE", "SharedPrefsSetupStateStore: markCompleted step=$key newValue=true")
        val success = prefs.edit().putBoolean(key, true).commit()
        logger.d("STATE", "SharedPrefsSetupStateStore: markCompleted step=$key commit=${if (success) "success" else "failure"}")
    }

    override fun clearCompleted(key: String) {
        logger.d("STATE", "SharedPrefsSetupStateStore: clearCompleted step=$key (single step clear)")
        val success = prefs.edit().remove(key).commit()
        logger.d("STATE", "SharedPrefsSetupStateStore: clearCompleted step=$key commit=${if (success) "success" else "failure"}")
    }

    override fun isCompleted(key: String): Boolean {
        val value = prefs.getBoolean(key, false)
        logger.d("STATE", "SharedPrefsSetupStateStore: isCompleted step=$key cachedValue=$value")
        return value
    }

    override fun getStoredVersion(): String {
        val version = prefs.getString("script_version", "0") ?: "0"
        logger.d("STATE", "SharedPrefsSetupStateStore: getStoredVersion cachedValue=$version")
        return version
    }

    override fun setStoredVersion(v: String) {
        logger.d("STATE", "SharedPrefsSetupStateStore: setStoredVersion step=script_version newValue=$v")
        val success = prefs.edit().putString("script_version", v).commit()
        logger.d("STATE", "SharedPrefsSetupStateStore: setStoredVersion commit=${if (success) "success" else "failure"}")
    }

    override fun isVersionCurrent(): Boolean {
        val current = getStoredVersion() == SetupStateStore.CURRENT_SCRIPT_VERSION
        logger.d("STATE", "SharedPrefsSetupStateStore: isVersionCurrent result=$current")
        return current
    }

    override fun clearAll() {
        logger.d("STATE", "SharedPrefsSetupStateStore: clearAll (full clear)")
        val success = prefs.edit().clear().commit()
        logger.d("STATE", "SharedPrefsSetupStateStore: clearAll commit=${if (success) "success" else "failure"}")
    }
}

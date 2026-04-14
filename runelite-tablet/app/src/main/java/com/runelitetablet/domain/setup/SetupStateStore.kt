package com.runelitetablet.domain.setup

/**
 * Setup state persistence abstraction. Pure Kotlin — no Android imports.
 * Production: SharedPrefsSetupStateStore (SharedPreferences).
 * Tests: FakeSetupStateStore (in-memory HashMap).
 */
interface SetupStateStore {
    fun markCompleted(key: String)
    fun clearCompleted(key: String)
    fun isCompleted(key: String): Boolean
    fun getStoredVersion(): String
    fun setStoredVersion(v: String)
    fun isVersionCurrent(): Boolean
    fun clearAll()

    companion object {
        const val CURRENT_SCRIPT_VERSION = "7"
    }
}

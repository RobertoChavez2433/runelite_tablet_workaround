package com.runelitetablet.testutil

import com.runelitetablet.domain.setup.SetupStateStore

/**
 * Android boundary fake — replaces SharedPrefsSetupStateStore.
 * Can't use Android SharedPreferences on a laptop.
 *
 * In-memory step completion tracking.
 */
class FakeSetupStateStore : SetupStateStore {
    private val completed = mutableMapOf<String, Boolean>()
    private var storedVersion: String = "0"

    override fun markCompleted(key: String) { completed[key] = true }
    override fun clearCompleted(key: String) { completed.remove(key) }
    override fun isCompleted(key: String): Boolean = completed[key] == true
    override fun getStoredVersion(): String = storedVersion
    override fun setStoredVersion(v: String) { storedVersion = v }
    override fun isVersionCurrent(): Boolean = storedVersion == SetupStateStore.CURRENT_SCRIPT_VERSION
    override fun clearAll() { completed.clear(); storedVersion = "0" }
}

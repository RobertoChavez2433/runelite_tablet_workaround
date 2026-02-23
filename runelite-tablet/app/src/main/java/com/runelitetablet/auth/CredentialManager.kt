package com.runelitetablet.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.runelitetablet.logging.AppLog
import java.security.GeneralSecurityException

/**
 * Secure credential storage using EncryptedSharedPreferences.
 *
 * Stores Jagex OAuth2 tokens and game session credentials.
 * Never logs credential values — only masks them (JX_SESSION_ID=***).
 *
 * Lazy initialization: the MasterKey and EncryptedSharedPreferences are created
 * on first access, not at construction. This survives Keystore unavailability
 * (e.g., device locked after reboot).
 */
class CredentialManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "jagex_credentials"
        private const val KEY_SESSION_ID = "JX_SESSION_ID"
        private const val KEY_CHARACTER_ID = "JX_CHARACTER_ID"
        private const val KEY_DISPLAY_NAME = "JX_DISPLAY_NAME"
        private const val KEY_ACCESS_TOKEN = "JX_ACCESS_TOKEN"
        private const val KEY_REFRESH_TOKEN = "JX_REFRESH_TOKEN"
        private const val KEY_ACCESS_TOKEN_EXPIRY = "access_token_expiry"
    }

    private var _prefs: SharedPreferences? = null

    /**
     * Access token is kept in memory only — never persisted to EncryptedSharedPreferences.
     * On cold start the caller must refresh via refresh_token to obtain a new access token.
     */
    @Volatile
    private var inMemoryAccessToken: String? = null

    /**
     * Get the encrypted SharedPreferences, lazily initializing on first call.
     * Returns null if Keystore is unavailable (device locked).
     *
     * @Synchronized provides both visibility and atomicity, eliminating the need
     * for @Volatile and the racy check-then-set pattern.
     */
    @Synchronized
    private fun getPrefs(): SharedPreferences? {
        _prefs?.let { return it }
        return try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { _prefs = it }
        } catch (e: GeneralSecurityException) {
            AppLog.e("AUTH", "CredentialManager: Keystore unavailable: ${e.message}", e)
            null
        } catch (e: Exception) {
            AppLog.e("AUTH", "CredentialManager: Failed to init EncryptedSharedPreferences: ${e.message}", e)
            null
        }
    }

    /**
     * Check if we have stored credentials (at minimum, a session ID).
     */
    fun hasCredentials(): Boolean {
        return getPrefs()?.getString(KEY_SESSION_ID, null) != null
    }

    /**
     * Check if the Keystore is available for credential operations.
     */
    fun isKeystoreAvailable(): Boolean {
        return getPrefs() != null
    }

    /**
     * Store tokens from a successful OAuth2 token exchange or refresh.
     */
    fun storeTokens(response: TokenResponse) {
        val prefs = getPrefs() ?: run {
            AppLog.e("AUTH", "storeTokens: Keystore unavailable")
            return
        }
        // Keep access_token in memory only — never persisted to disk
        inMemoryAccessToken = response.accessToken
        prefs.edit()
            .also { editor ->
                response.refreshToken?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
            }
            .putLong(KEY_ACCESS_TOKEN_EXPIRY, response.accessTokenExpiry)
            .apply()
        AppLog.step("auth", "storeTokens: tokens stored (access_token in-memory only), expiry=${response.accessTokenExpiry}")
    }

    /**
     * Store game session credentials after character selection.
     */
    fun storeGameSession(sessionId: String, characterId: String, displayName: String) {
        val prefs = getPrefs() ?: run {
            AppLog.e("AUTH", "storeGameSession: Keystore unavailable")
            return
        }
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_CHARACTER_ID, characterId)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
        AppLog.step("auth", "storeGameSession: session stored for character ***")
    }

    /**
     * Get all stored credentials, or null if incomplete.
     */
    fun getCredentials(): JagexCredentials? {
        val prefs = getPrefs() ?: return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val characterId = prefs.getString(KEY_CHARACTER_ID, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: return null
        return JagexCredentials(
            sessionId = sessionId,
            characterId = characterId,
            displayName = displayName,
            accessToken = inMemoryAccessToken,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        )
    }

    /**
     * Get the display name of the currently authenticated user, or null.
     */
    fun getDisplayName(): String? {
        return getPrefs()?.getString(KEY_DISPLAY_NAME, null)
    }

    /**
     * Get the stored access token expiry as Unix seconds.
     */
    fun getAccessTokenExpiry(): Long {
        return getPrefs()?.getLong(KEY_ACCESS_TOKEN_EXPIRY, 0L) ?: 0L
    }

    /**
     * Get the stored refresh token, or null.
     */
    fun getRefreshToken(): String? {
        return getPrefs()?.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Clear all stored credentials. Used on sign-out or session expiry.
     */
    fun clearCredentials() {
        inMemoryAccessToken = null
        _prefs?.edit()?.clear()?.apply()
        _prefs = null
        AppLog.step("auth", "clearCredentials: all credentials cleared")
    }
}

/**
 * Holder for all Jagex credentials needed for RuneLite launch.
 */
data class JagexCredentials(
    val sessionId: String,
    val characterId: String,
    val displayName: String,
    val accessToken: String?,
    val refreshToken: String?
) {
    override fun toString(): String = "JagexCredentials(displayName=$displayName, [REDACTED])"
}

package com.runelitetablet.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.auth.Credentials
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
class CredentialManager(private val context: Context) : CredentialStore {

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
    private var prefsRecreateAttempted = false

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
            AppLog.e("AUTH", "CredentialManager: Keystore error: ${e.message}", e)
            // Corrupted prefs file — delete it and retry once
            return tryDeleteAndRecreatePrefs(e)
        } catch (e: Exception) {
            AppLog.e("AUTH", "CredentialManager: Failed to init EncryptedSharedPreferences: ${e.message}", e)
            // Corrupted prefs file — delete it and retry once
            return tryDeleteAndRecreatePrefs(e)
        }
    }

    /**
     * Check if we have stored credentials (at minimum, a session ID).
     */
    /**
     * Handle corrupted EncryptedSharedPreferences file by deleting and retrying once.
     * Prevents permanent bricking of credential storage after power loss or disk corruption.
     */
    private fun tryDeleteAndRecreatePrefs(originalError: Exception): SharedPreferences? {
        if (prefsRecreateAttempted) {
            AppLog.e("AUTH", "CredentialManager: already attempted prefs recreation — giving up")
            return null
        }
        prefsRecreateAttempted = true
        AppLog.w("AUTH", "CredentialManager: deleting corrupted prefs file and retrying")
        try {
            val prefsFile = java.io.File(context.filesDir.parent, "shared_prefs/$PREFS_NAME.xml")
            if (prefsFile.exists()) prefsFile.delete()
        } catch (e: Exception) {
            AppLog.e("AUTH", "CredentialManager: failed to delete corrupted prefs file: ${e.message}")
        }
        return try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { _prefs = it }
        } catch (e: Exception) {
            AppLog.e("AUTH", "CredentialManager: prefs recreation also failed: ${e.message}")
            null
        }
    }

    override fun hasCredentials(): Boolean {
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
        storeTokens(response.accessToken, response.refreshToken, response.accessTokenExpiry)
    }

    override fun storeTokens(accessToken: String?, refreshToken: String?, accessTokenExpiry: Long) {
        val prefs = getPrefs() ?: run {
            AppLog.e("AUTH", "storeTokens: Keystore unavailable")
            return
        }
        inMemoryAccessToken = accessToken
        prefs.edit()
            .also { editor ->
                refreshToken?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
            }
            .putLong(KEY_ACCESS_TOKEN_EXPIRY, accessTokenExpiry)
            .apply()
        AppLog.step("auth", "storeTokens: tokens stored (access_token in-memory only), expiry=$accessTokenExpiry")
    }

    /**
     * Store game session credentials after character selection.
     */
    override fun storeGameSession(sessionId: String, characterId: String, displayName: String) {
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
    override fun getCredentials(): Credentials? {
        val prefs = getPrefs() ?: return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val characterId = prefs.getString(KEY_CHARACTER_ID, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: return null
        return Credentials(
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
    override fun getDisplayName(): String? {
        return getPrefs()?.getString(KEY_DISPLAY_NAME, null)
    }

    /**
     * Get the stored access token expiry as Unix seconds.
     */
    override fun getAccessTokenExpiry(): Long {
        return getPrefs()?.getLong(KEY_ACCESS_TOKEN_EXPIRY, 0L) ?: 0L
    }

    /**
     * Get the stored refresh token, or null.
     */
    override fun getRefreshToken(): String? {
        return getPrefs()?.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Clear all stored credentials. Used on sign-out or session expiry.
     */
    override fun clearCredentials() {
        inMemoryAccessToken = null
        _prefs?.edit()?.clear()?.apply()
        _prefs = null
        AppLog.step("auth", "clearCredentials: all credentials cleared")
    }
}

/** Alias for backward compatibility — use Credentials from domain for new code. */
typealias JagexCredentials = Credentials

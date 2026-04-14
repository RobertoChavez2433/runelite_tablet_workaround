package com.runelitetablet.domain.auth

/**
 * Credential storage abstraction. Pure Kotlin — no Android imports.
 * Production: CredentialManager (EncryptedSharedPreferences + Keystore).
 * Tests: FakeCredentialStore (in-memory HashMap).
 */
interface CredentialStore {
    fun storeTokens(accessToken: String?, refreshToken: String?, accessTokenExpiry: Long)
    fun storeGameSession(sessionId: String, characterId: String, displayName: String)
    fun getCredentials(): Credentials?
    fun getDisplayName(): String?
    fun getAccessTokenExpiry(): Long
    fun getRefreshToken(): String?
    fun hasCredentials(): Boolean
    fun clearCredentials()
}

/**
 * All credentials needed for RuneLite launch.
 * Moved from auth/CredentialManager.kt to domain for interface use.
 */
data class Credentials(
    val sessionId: String,
    val characterId: String,
    val displayName: String,
    val accessToken: String?,
    val refreshToken: String?
) {
    override fun toString(): String = "Credentials(displayName=$displayName, [REDACTED])"
}

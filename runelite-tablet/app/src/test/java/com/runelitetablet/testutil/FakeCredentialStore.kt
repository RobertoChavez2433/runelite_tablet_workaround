package com.runelitetablet.testutil

import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.auth.Credentials

/**
 * Android boundary fake — replaces CredentialManager.
 * Can't use Android Keystore hardware encryption on a laptop.
 *
 * In-memory HashMap token storage.
 */
class FakeCredentialStore : CredentialStore {
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var accessTokenExpiry: Long = 0L
    private var sessionId: String? = null
    private var characterId: String? = null
    private var displayName: String? = null

    override fun storeTokens(accessToken: String?, refreshToken: String?, accessTokenExpiry: Long) {
        this.accessToken = accessToken
        if (refreshToken != null) this.refreshToken = refreshToken
        this.accessTokenExpiry = accessTokenExpiry
    }

    override fun storeGameSession(sessionId: String, characterId: String, displayName: String) {
        this.sessionId = sessionId
        this.characterId = characterId
        this.displayName = displayName
    }

    override fun getCredentials(): Credentials? {
        val sid = sessionId ?: return null
        val cid = characterId ?: return null
        val name = displayName ?: return null
        return Credentials(sid, cid, name, accessToken, refreshToken)
    }

    override fun getDisplayName(): String? = displayName

    override fun getAccessTokenExpiry(): Long = accessTokenExpiry

    override fun getRefreshToken(): String? = refreshToken

    override fun hasCredentials(): Boolean = sessionId != null

    override fun clearCredentials() {
        accessToken = null
        refreshToken = null
        accessTokenExpiry = 0L
        sessionId = null
        characterId = null
        displayName = null
    }
}

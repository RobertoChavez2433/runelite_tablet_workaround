package com.runelitetablet.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (Proof Key for Code Exchange) helper for OAuth2 authorization code flow.
 * Generates code_verifier and derives code_challenge per RFC 7636.
 */
object PkceHelper {

    /**
     * Generate a random code_verifier: 64 random bytes, base64url-encoded.
     * The verifier lives in the ViewModel for the duration of the auth session
     * and is discarded after token exchange (success or failure). Never persisted.
     */
    fun generateVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Derive code_challenge from code_verifier using SHA-256 per S256 method.
     */
    fun deriveChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Generate a random state nonce for CSRF protection.
     */
    fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

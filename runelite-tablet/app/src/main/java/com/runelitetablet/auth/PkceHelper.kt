package com.runelitetablet.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (Proof Key for Code Exchange) helper for OAuth2 authorization code flow.
 * Generates code_verifier and derives code_challenge per RFC 7636.
 *
 * Pure JVM — uses java.util.Base64 (available on Android API 26+, JVM 8+)
 * so this class is testable without Android framework.
 */
object PkceHelper {

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Generate a random code_verifier: 64 random bytes, base64url-encoded.
     * The verifier lives in the ViewModel for the duration of the auth session
     * and is discarded after token exchange (success or failure). Never persisted.
     */
    fun generateVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    /**
     * Derive code_challenge from code_verifier using SHA-256 per S256 method.
     */
    fun deriveChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    /**
     * Generate a random state nonce for CSRF protection.
     */
    fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }
}

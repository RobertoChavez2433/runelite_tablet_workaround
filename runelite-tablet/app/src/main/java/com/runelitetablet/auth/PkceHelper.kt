package com.runelitetablet.auth

import com.runelitetablet.domain.logging.Logger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (Proof Key for Code Exchange) helper for OAuth2 authorization code flow.
 * Generates code_verifier and derives code_challenge per RFC 7636.
 *
 * Pure JVM — uses java.util.Base64 (available on Android API 26+, JVM 8+)
 * so this class is testable without Android framework.
 *
 * Logger is optional — pass null (default) in tests to keep pure JVM testability.
 * Security: never logs actual verifier/challenge values, only lengths.
 */
object PkceHelper {

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Generate a random code_verifier: 64 random bytes, base64url-encoded.
     * The verifier lives in the ViewModel for the duration of the auth session
     * and is discarded after token exchange (success or failure). Never persisted.
     */
    fun generateVerifier(logger: Logger? = null): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        val verifier = urlEncoder.encodeToString(bytes)
        logger?.d("AUTH", "PkceHelper: verifier generated length=${verifier.length}")
        return verifier
    }

    /**
     * Derive code_challenge from code_verifier using SHA-256 per S256 method.
     */
    fun deriveChallenge(verifier: String, logger: Logger? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = urlEncoder.encodeToString(digest)
        logger?.d("AUTH", "PkceHelper: challenge computed method=S256 length=${challenge.length}")
        return challenge
    }

    /**
     * Generate a random state nonce for CSRF protection.
     */
    fun generateState(logger: Logger? = null): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val state = urlEncoder.encodeToString(bytes)
        logger?.d("AUTH", "PkceHelper: state nonce generated length=${state.length}")
        return state
    }
}

package com.runelitetablet.auth

import com.runelitetablet.logging.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JWT claim parsing — no network calls.
 */
object JwtHelper {

    private val json = Json { ignoreUnknownKeys = true }

    /** Nonce length for Step 2 consent (48 alphanumeric characters). */
    private const val NONCE_LENGTH = 48
    private val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val secureRandom = java.security.SecureRandom()

    /**
     * Extract the login_provider claim from a JWT id_token.
     * Returns "jagex" or "runescape". Defaults to "jagex" if claim is missing.
     */
    fun parseLoginProvider(idToken: String): String {
        return parseJwtClaim(idToken, "login_provider") ?: "jagex"
    }

    fun verifyNonce(idToken: String, expectedNonce: String): Boolean {
        val actual = parseJwtClaim(idToken, "nonce") ?: return false
        return actual == expectedNonce
    }

    /**
     * Parse a specific claim from a JWT token's payload.
     * JWT format: header.payload.signature (base64url encoded).
     * Returns the claim value as a string, or null if parsing fails.
     */
    fun parseJwtClaim(jwt: String, claim: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payload = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val payloadJson = String(payload, Charsets.UTF_8)
            val jsonObj = json.parseToJsonElement(payloadJson).jsonObject
            jsonObj[claim]?.jsonPrimitive?.content
        } catch (e: IllegalArgumentException) {
            AppLog.w("AUTH", "parseJwtClaim($claim): failed: ${e.message}")
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            AppLog.w("AUTH", "parseJwtClaim($claim): failed: ${e.message}")
            null
        }
    }

    /**
     * Generate a random nonce for Step 2 consent (48 alphanumeric characters).
     */
    fun generateNonce(): String {
        return buildString(NONCE_LENGTH) {
            repeat(NONCE_LENGTH) {
                append(NONCE_CHARS[secureRandom.nextInt(NONCE_CHARS.length)])
            }
        }
    }
}

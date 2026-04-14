package com.runelitetablet.auth

import java.io.IOException

/**
 * Token response from OAuth2 token exchange or refresh.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresIn: Long,
    val accessTokenExpiry: Long // Unix seconds
) {
    override fun toString(): String = "TokenResponse(expiresIn=$expiresIn, hasIdToken=${idToken != null}, [REDACTED])"
}

/**
 * Represents a game character from the accounts endpoint.
 */
data class GameCharacter(
    val accountId: String,
    val displayName: String
)

/**
 * OAuth2 error with HTTP status code and optional error body.
 */
class OAuthException(
    val httpCode: Int,
    message: String,
    val errorBody: String
) : Exception(message) {
    /** Never expose errorBody in toString() to prevent accidental credential leakage in logs. */
    override fun toString(): String = "OAuthException(httpCode=$httpCode, message=$message)"
}

/**
 * Result of pre-launch session validation.
 * Tri-state ensures the caller can distinguish "expired" (clear creds, re-auth)
 * from "network unreachable" (log, continue with existing creds).
 */
sealed class SessionValidation {
    /** Session is valid (200 from accounts API). */
    object Valid : SessionValidation()
    /** Session expired server-side (401/403). Must re-auth via GeckoView. */
    object Expired : SessionValidation()
    /** Network error — cannot determine session status. Don't clear creds. */
    data class NetworkError(val exception: Exception) : SessionValidation()
}

/**
 * Result of a pre-launch token refresh check.
 */
sealed class AuthResult {
    /** Token is still valid, no refresh needed. */
    object Valid : AuthResult()
    /** Token was refreshed successfully. */
    object Refreshed : AuthResult()
    /** Refresh token expired or invalid — user must re-login. */
    object NeedsLogin : AuthResult()
    /** Network or other transient error. */
    data class NetworkError(val exception: Exception) : AuthResult()
}

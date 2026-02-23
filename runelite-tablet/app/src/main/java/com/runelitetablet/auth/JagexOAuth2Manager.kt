package com.runelitetablet.auth

import android.net.Uri
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages Jagex OAuth2 authentication — correct 2-step, 2-client-ID flow.
 *
 * Step 1 (Account Authentication):
 *   - Client: com_jagex_auth_desktop_launcher
 *   - Redirect: https://secure.runescape.com/m=weblogin/launcher-redirect
 *   - Captured via jagex: custom URI scheme (intent filter in AndroidManifest)
 *   - PKCE S256
 *   - Returns access_token, refresh_token, id_token
 *
 * Step 2 (Consent — Jagex accounts only):
 *   - Client: 1fddee4e-b100-4f4e-b2b0-097f9088f9d2
 *   - Redirect: http://localhost:<port> (captured via LocalhostAuthServer)
 *   - No PKCE, nonce instead
 *   - response_type: id_token code (hybrid)
 *   - Returns id_token in URL fragment (captured by forwarder HTML)
 *
 * Step 3 (Game Session — API calls, no browser):
 *   - POST id_token to /sessions -> sessionId
 *   - GET /accounts with Bearer sessionId -> character list
 */
class JagexOAuth2Manager(private val httpClient: OkHttpClient) {

    companion object {
        /** Max length for error body excerpts in logs/exceptions to prevent credential leakage. */
        private const val MAX_ERROR_BODY_LEN = 200
        /** Patterns that look like tokens/secrets — redacted before logging. */
        private val SENSITIVE_PATTERNS = listOf(
            Regex("""(access_token|refresh_token|id_token|session_id|authorization|bearer)\s*[=:]\s*\S+""", RegexOption.IGNORE_CASE),
            Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""") // JWT
        )

        // OAuth2 endpoints
        private const val AUTH_ENDPOINT = "https://account.jagex.com/oauth2/auth"
        private const val TOKEN_ENDPOINT = "https://account.jagex.com/oauth2/token"

        // Step 1: Launcher client — account authentication
        private const val LAUNCHER_CLIENT_ID = "com_jagex_auth_desktop_launcher"
        private const val LAUNCHER_REDIRECT_URI = "https://secure.runescape.com/m=weblogin/launcher-redirect"
        private const val LAUNCHER_SCOPES = "openid offline gamesso.token.create user.profile.read"

        // Step 2: Consent client — Jagex account consent
        private const val CONSENT_CLIENT_ID = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2"
        private const val CONSENT_SCOPES = "openid offline"

        // Game session endpoints
        private const val ACCOUNTS_ENDPOINT = "https://auth.jagex.com/game-session/v1/accounts"
        private const val SESSIONS_ENDPOINT = "https://auth.jagex.com/game-session/v1/sessions"

        /** Nonce length for Step 2 consent (48 alphanumeric characters). */
        private const val NONCE_LENGTH = 48
        private val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val secureRandom = SecureRandom()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Step 1: Build auth URL (launcher client)
    // -------------------------------------------------------------------------

    /**
     * Build the Step 1 authorization URL for Chrome Custom Tab.
     * Uses the launcher client with PKCE. The redirect goes to Jagex's launcher-redirect
     * page, which triggers a jagex: custom URI scheme redirect captured by our intent filter.
     *
     * @param codeVerifier PKCE code verifier
     * @param state CSRF protection nonce
     */
    fun buildStep1AuthUrl(codeVerifier: String, state: String): Uri {
        val codeChallenge = PkceHelper.deriveChallenge(codeVerifier)
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", LAUNCHER_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", LAUNCHER_REDIRECT_URI)
            .appendQueryParameter("scope", LAUNCHER_SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
    }

    // -------------------------------------------------------------------------
    // Step 2: Build consent URL (consent client)
    // -------------------------------------------------------------------------

    /**
     * Build the Step 2 consent URL for Chrome Custom Tab.
     * Uses the consent client with hybrid response_type (id_token code).
     * No PKCE — uses nonce for replay protection.
     * Redirect to localhost where forwarder HTML extracts fragment params.
     *
     * @param localPort Port of the LocalhostAuthServer
     * @param state CSRF protection nonce
     * @param nonce Nonce for id_token replay protection (48 alphanumeric chars)
     */
    fun buildStep2ConsentUrl(localPort: Int, state: String, nonce: String): Uri {
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CONSENT_CLIENT_ID)
            .appendQueryParameter("response_type", "id_token code")
            .appendQueryParameter("redirect_uri", "http://localhost:$localPort")
            .appendQueryParameter("scope", CONSENT_SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .build()
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

    // -------------------------------------------------------------------------
    // Step 1: Token exchange (launcher client)
    // -------------------------------------------------------------------------

    /**
     * Exchange Step 1 authorization code for tokens.
     * POST to token endpoint with authorization_code grant type + PKCE verifier.
     * Uses the launcher client_id and the launcher redirect_uri.
     */
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String
    ): TokenResponse = withContext(Dispatchers.IO) {
        AppLog.step("auth", "exchangeCodeForTokens: exchanging code for tokens")

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", LAUNCHER_REDIRECT_URI)
            .add("client_id", LAUNCHER_CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()

        httpClient.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val sanitized = sanitizeErrorBody(errorBody)
                AppLog.e("AUTH", "exchangeCodeForTokens: HTTP ${response.code} — $sanitized")
                throw OAuthException(response.code, "Token exchange failed: HTTP ${response.code}", sanitized)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")
            parseTokenResponse(responseBody)
        }
    }

    /**
     * Refresh tokens using a refresh_token grant.
     * Uses the launcher client_id.
     */
    suspend fun refreshTokens(refreshToken: String): TokenResponse = withContext(Dispatchers.IO) {
        AppLog.step("auth", "refreshTokens: refreshing access token")

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", LAUNCHER_CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()

        httpClient.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val sanitized = sanitizeErrorBody(errorBody)
                AppLog.e("AUTH", "refreshTokens: HTTP ${response.code} — $sanitized")
                throw OAuthException(response.code, "Token refresh failed: HTTP ${response.code}", sanitized)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")
            parseTokenResponse(responseBody)
        }
    }

    // -------------------------------------------------------------------------
    // JWT parsing
    // -------------------------------------------------------------------------

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
    private fun parseJwtClaim(jwt: String, claim: String): String? {
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

    // -------------------------------------------------------------------------
    // Step 3: Game session APIs (correct auth method)
    // -------------------------------------------------------------------------

    /**
     * Create a game session by POSTing the id_token.
     * This is Step 3a — the id_token comes from Step 2 consent.
     *
     * @param idToken JWT from Step 2 consent
     * @return sessionId to use as Bearer token for subsequent API calls
     */
    suspend fun createGameSession(idToken: String): String = withContext(Dispatchers.IO) {
        AppLog.step("auth", "createGameSession: posting id_token to sessions endpoint")

        val jsonBody = buildJsonObject { put("idToken", idToken) }.toString()
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SESSIONS_ENDPOINT)
            .post(body)
            .build()

        httpClient.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val sanitized = sanitizeErrorBody(errorBody)
                AppLog.e("AUTH", "createGameSession: HTTP ${response.code} — $sanitized")
                throw OAuthException(response.code, "Failed to create game session: HTTP ${response.code}", sanitized)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")

            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            jsonObj["sessionId"]?.jsonPrimitive?.content
                ?: throw OAuthException(0, "No sessionId in response", "Response keys: ${jsonObj.keys.joinToString()}")
        }
    }

    /**
     * Fetch the list of characters (accounts) using the sessionId as Bearer token.
     * This is Step 3b — the sessionId comes from createGameSession().
     *
     * @param sessionId Bearer token from createGameSession()
     * @return List of game characters on this account
     */
    suspend fun fetchAccounts(sessionId: String): List<GameCharacter> = withContext(Dispatchers.IO) {
        AppLog.step("auth", "fetchAccounts: fetching character list with session bearer")

        val request = Request.Builder()
            .url(ACCOUNTS_ENDPOINT)
            .header("Authorization", "Bearer $sessionId")
            .get()
            .build()

        httpClient.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val sanitized = sanitizeErrorBody(errorBody)
                AppLog.e("AUTH", "fetchAccounts: HTTP ${response.code} — $sanitized")
                throw OAuthException(response.code, "Failed to fetch accounts: HTTP ${response.code}", sanitized)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")

            val jsonArray = json.parseToJsonElement(responseBody).jsonArray
            jsonArray.map { element ->
                val obj = element.jsonObject
                GameCharacter(
                    accountId = obj["accountId"]?.jsonPrimitive?.content ?: "",
                    displayName = obj["displayName"]?.jsonPrimitive?.content ?: ""
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Truncate and sanitize an error body before logging or embedding in exceptions.
     * Strips token-like patterns and limits length to prevent credential leakage.
     */
    private fun sanitizeErrorBody(body: String): String {
        var sanitized = body.take(MAX_ERROR_BODY_LEN)
        for (pattern in SENSITIVE_PATTERNS) {
            sanitized = pattern.replace(sanitized, "[REDACTED]")
        }
        if (body.length > MAX_ERROR_BODY_LEN) {
            sanitized += "...[truncated]"
        }
        return sanitized
    }

    /**
     * Execute an OkHttp call with coroutine cancellation support.
     * Uses enqueue() + suspendCancellableCoroutine so that cancelling the coroutine
     * also cancels the underlying HTTP call (freeing the IO thread immediately).
     */
    private suspend fun Call.executeCancellable(): Response {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { this.cancel() }
            this.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCompleted) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isCompleted) cont.resume(response)
                }
            })
        }
    }

    private fun parseTokenResponse(responseBody: String): TokenResponse {
        val jsonObj = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content
            ?: throw OAuthException(0, "No access_token in response", "Response keys: ${jsonObj.keys.joinToString()}")
        val refreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.content
        val idToken = jsonObj["id_token"]?.jsonPrimitive?.content
        val expiresIn = jsonObj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

        // Parse JWT exp claim for precise expiry tracking
        val expiry = parseJwtClaim(accessToken, "exp")?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000L + expiresIn)

        AppLog.step("auth", "parseTokenResponse: token received, expiresIn=${expiresIn}s expiry=$expiry hasIdToken=${idToken != null}")
        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresIn = expiresIn,
            accessTokenExpiry = expiry
        )
    }
}

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

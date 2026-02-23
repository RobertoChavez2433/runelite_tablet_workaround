package com.runelitetablet.auth

import android.net.Uri
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Manages Jagex OAuth2 authentication flow.
 *
 * Part A: Account authentication (browser-based via Chrome Custom Tab)
 *   - Authorization code + PKCE flow
 *   - Client ID: com_jagex_auth_desktop_launcher
 *   - Redirect: https://secure.runescape.com/m=weblogin/launcher-redirect
 *
 * Part B: Game session (direct API calls, no browser)
 *   - Fetch character list
 *   - Create game session -> JX_SESSION_ID
 *
 * If Spike B determines second OAuth2 step is needed:
 *   - Second browser flow with client_id 1fddee4e-b100-4f4e-b2b0-097f9088f9d2
 *   - Redirect to http://localhost (captured via ServerSocket)
 */
class JagexOAuth2Manager(private val httpClient: OkHttpClient) {

    companion object {
        // Part A: Account authentication
        private const val AUTH_ENDPOINT = "https://account.jagex.com/oauth2/auth"
        private const val TOKEN_ENDPOINT = "https://account.jagex.com/oauth2/token"
        private const val CLIENT_ID = "com_jagex_auth_desktop_launcher"
        const val REDIRECT_URI = "https://secure.runescape.com/m=weblogin/launcher-redirect"
        private const val SCOPES = "openid offline gamesso.token.create user.profile.read"

        // Part B: Game session
        private const val ACCOUNTS_ENDPOINT = "https://auth.jagex.com/game-session/v1/accounts"
        private const val SESSIONS_ENDPOINT = "https://auth.jagex.com/game-session/v1/sessions"

        // Second OAuth2 step (if needed per Spike B)
        private const val SECOND_CLIENT_ID = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2"
        private const val SECOND_SCOPES = "openid offline gamesso.token.create user.profile.read"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Part A: Build auth URL
    // -------------------------------------------------------------------------

    /**
     * Build the authorization URL for the Chrome Custom Tab.
     * Uses Option 2 redirect (documented web URL, intercepted via CustomTabsCallback).
     */
    fun buildAuthUrl(codeVerifier: String, state: String): Uri {
        val codeChallenge = PkceHelper.deriveChallenge(codeVerifier)
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
    }

    /**
     * Build the second OAuth2 authorization URL (if second step is required).
     * Uses localhost redirect captured via ServerSocket.
     */
    fun buildSecondAuthUrl(codeVerifier: String, state: String, localPort: Int): Uri {
        val codeChallenge = PkceHelper.deriveChallenge(codeVerifier)
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", SECOND_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "http://localhost:$localPort")
            .appendQueryParameter("scope", SECOND_SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
    }

    // -------------------------------------------------------------------------
    // Part A: Token exchange
    // -------------------------------------------------------------------------

    /**
     * Exchange authorization code for tokens.
     * POST to token endpoint with authorization_code grant type + PKCE verifier.
     */
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): TokenResponse = withContext(Dispatchers.IO) {
        AppLog.step("auth", "exchangeCodeForTokens: exchanging code for tokens")

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("AUTH", "exchangeCodeForTokens: HTTP ${response.code} — $errorBody")
                throw OAuthException(response.code, "Token exchange failed: HTTP ${response.code}", errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")
            parseTokenResponse(responseBody)
        }
    }

    /**
     * Exchange authorization code for tokens using the second client ID.
     */
    suspend fun exchangeSecondCode(
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): TokenResponse = withContext(Dispatchers.IO) {
        AppLog.step("auth", "exchangeSecondCode: exchanging second auth code")

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", SECOND_CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("AUTH", "exchangeSecondCode: HTTP ${response.code} — $errorBody")
                throw OAuthException(response.code, "Second token exchange failed: HTTP ${response.code}", errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")
            parseTokenResponse(responseBody)
        }
    }

    /**
     * Refresh tokens using a refresh_token grant.
     */
    suspend fun refreshTokens(refreshToken: String): TokenResponse = withContext(Dispatchers.IO) {
        AppLog.step("auth", "refreshTokens: refreshing access token")

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("AUTH", "refreshTokens: HTTP ${response.code} — $errorBody")
                throw OAuthException(response.code, "Token refresh failed: HTTP ${response.code}", errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")
            parseTokenResponse(responseBody)
        }
    }

    // -------------------------------------------------------------------------
    // Part B: Game session
    // -------------------------------------------------------------------------

    /**
     * Fetch the list of characters associated with this account.
     */
    suspend fun fetchCharacters(accessToken: String): List<GameCharacter> = withContext(Dispatchers.IO) {
        AppLog.step("auth", "fetchCharacters: fetching character list")

        val request = Request.Builder()
            .url(ACCOUNTS_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("AUTH", "fetchCharacters: HTTP ${response.code} — $errorBody")
                throw OAuthException(response.code, "Failed to fetch characters: HTTP ${response.code}", errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")

            val jsonArray = Json.parseToJsonElement(responseBody).jsonArray
            jsonArray.map { element ->
                val obj = element.jsonObject
                GameCharacter(
                    accountId = obj["accountId"]?.jsonPrimitive?.content ?: "",
                    displayName = obj["displayName"]?.jsonPrimitive?.content ?: ""
                )
            }
        }
    }

    /**
     * Create a game session for the selected character.
     * Returns JX_SESSION_ID.
     */
    suspend fun createGameSession(
        accessToken: String,
        accountId: String
    ): String = withContext(Dispatchers.IO) {
        AppLog.step("auth", "createGameSession: creating session for account ***")

        val jsonBody = JSONObject().apply {
            put("accountId", accountId)
        }.toString()
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(SESSIONS_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("AUTH", "createGameSession: HTTP ${response.code} — $errorBody")
                throw OAuthException(response.code, "Failed to create game session: HTTP ${response.code}", errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw OAuthException(0, "Empty response body", "")

            val jsonObj = Json.parseToJsonElement(responseBody).jsonObject
            jsonObj["sessionId"]?.jsonPrimitive?.content
                ?: throw OAuthException(0, "No sessionId in response", responseBody)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun parseTokenResponse(responseBody: String): TokenResponse {
        val jsonObj = Json.parseToJsonElement(responseBody).jsonObject
        val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content
            ?: throw OAuthException(0, "No access_token in response", responseBody)
        val refreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.content
        val expiresIn = jsonObj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

        // Parse JWT exp claim for precise expiry tracking
        val expiry = parseJwtExpiry(accessToken) ?: (System.currentTimeMillis() / 1000L + expiresIn)

        AppLog.step("auth", "parseTokenResponse: token received, expiresIn=${expiresIn}s expiry=$expiry")
        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
            accessTokenExpiry = expiry
        )
    }

    /**
     * Parse the exp claim from a JWT access token.
     * JWT format: header.payload.signature (base64url encoded).
     * Returns Unix timestamp in seconds, or null if parsing fails.
     */
    private fun parseJwtExpiry(jwt: String): Long? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            val payloadJson = String(payload, Charsets.UTF_8)
            val jsonObj = Json.parseToJsonElement(payloadJson).jsonObject
            jsonObj["exp"]?.jsonPrimitive?.content?.toLongOrNull()
        } catch (e: Exception) {
            AppLog.w("AUTH", "parseJwtExpiry: failed to parse JWT exp claim: ${e.message}")
            null
        }
    }
}

/**
 * Token response from OAuth2 token exchange or refresh.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val accessTokenExpiry: Long // Unix seconds
)

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
) : Exception(message)

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

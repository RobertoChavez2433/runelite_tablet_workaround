package com.runelitetablet.auth

import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JagexOAuth2Manager(
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        /** Max length for error body excerpts in logs/exceptions to prevent credential leakage. */
        private const val MAX_ERROR_BODY_LEN = 200
        /** Patterns that look like tokens/secrets — redacted before logging. */
        private val SENSITIVE_PATTERNS = listOf(
            Regex("""(access_token|refresh_token|id_token|session_id|authorization|bearer)\s*[=:]\s*\S+""", RegexOption.IGNORE_CASE),
            Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""") // JWT
        )

        private const val TOKEN_ENDPOINT = "https://account.jagex.com/oauth2/token"
        private const val LAUNCHER_CLIENT_ID = "com_jagex_auth_desktop_launcher"
        private const val LAUNCHER_REDIRECT_URI = "https://secure.runescape.com/m=weblogin/launcher-redirect"
        private const val ACCOUNTS_ENDPOINT = "https://auth.jagex.com/game-session/v1/accounts"
        private const val SESSIONS_ENDPOINT = "https://auth.jagex.com/game-session/v1/sessions"
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun buildStep1AuthUrl(codeVerifier: String, state: String) = OAuthUrls.buildStep1AuthUrl(codeVerifier, state)
    fun buildStep2ConsentUrl(state: String, nonce: String) = OAuthUrls.buildStep2ConsentUrl(state, nonce)
    fun generateNonce(): String = JwtHelper.generateNonce()

    suspend fun exchangeCodeForTokens(code: String, codeVerifier: String): TokenResponse = withContext(ioDispatcher) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", LAUNCHER_REDIRECT_URI).add("client_id", LAUNCHER_CLIENT_ID)
            .add("code_verifier", codeVerifier).build()
        parseTokenResponse(executeRequest(Request.Builder().url(TOKEN_ENDPOINT).post(body).build(), "exchangeCodeForTokens"))
    }

    suspend fun refreshTokens(refreshToken: String): TokenResponse = withContext(ioDispatcher) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", refreshToken)
            .add("client_id", LAUNCHER_CLIENT_ID).build()
        parseTokenResponse(executeRequest(Request.Builder().url(TOKEN_ENDPOINT).post(body).build(), "refreshTokens"))
    }

    fun parseLoginProvider(idToken: String): String = JwtHelper.parseLoginProvider(idToken)
    fun verifyNonce(idToken: String, expectedNonce: String): Boolean = JwtHelper.verifyNonce(idToken, expectedNonce)

    suspend fun createGameSession(idToken: String): String = withContext(ioDispatcher) {
        val body = buildJsonObject { put("idToken", idToken) }.toString().toRequestBody("application/json".toMediaType())
        val responseBody = executeRequest(Request.Builder().url(SESSIONS_ENDPOINT).post(body).build(), "createGameSession")
        json.parseToJsonElement(responseBody).jsonObject["sessionId"]?.jsonPrimitive?.content
            ?: throw OAuthException(0, "No sessionId in response", "")
    }

    suspend fun fetchAccounts(sessionId: String): List<GameCharacter> = withContext(ioDispatcher) {
        val request = Request.Builder().url(ACCOUNTS_ENDPOINT).header("Authorization", "Bearer $sessionId").get().build()
        val responseBody = executeRequest(request, "fetchAccounts")
        json.parseToJsonElement(responseBody).jsonArray.map { e ->
            val o = e.jsonObject
            GameCharacter(o["accountId"]?.jsonPrimitive?.content ?: "", o["displayName"]?.jsonPrimitive?.content ?: "")
        }
    }

    suspend fun validateSession(sessionId: String): SessionValidation = withContext(ioDispatcher) {
        AppLog.step("auth", "validateSession: checking session against accounts API")

        val request = Request.Builder()
            .url(ACCOUNTS_ENDPOINT)
            .header("Authorization", "Bearer $sessionId")
            .get()
            .build()

        try {
            httpClient.newCall(request).executeCancellable().use { response ->
                when {
                    response.isSuccessful -> {
                        AppLog.step("auth", "validateSession: session valid (${response.code})")
                        SessionValidation.Valid
                    }
                    response.code == 401 || response.code == 403 -> {
                        AppLog.step("auth", "validateSession: session expired (${response.code})")
                        SessionValidation.Expired
                    }
                    else -> {
                        AppLog.w("AUTH", "validateSession: unexpected HTTP ${response.code}")
                        SessionValidation.NetworkError(
                            IOException("Unexpected HTTP ${response.code} from session validation")
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppLog.w("AUTH", "validateSession: network error: ${e.message}")
            SessionValidation.NetworkError(e)
        }
    }

    private suspend fun executeRequest(request: Request, tag: String): String {
        httpClient.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) {
                val errorBody = sanitizeErrorBody(response.body?.string() ?: "")
                AppLog.e("AUTH", "$tag: HTTP ${response.code} — $errorBody")
                throw OAuthException(response.code, "$tag failed: HTTP ${response.code}", errorBody)
            }
            return response.body?.string() ?: throw OAuthException(0, "Empty response body", "")
        }
    }

    private fun sanitizeErrorBody(body: String): String {
        var s = body.take(MAX_ERROR_BODY_LEN)
        for (p in SENSITIVE_PATTERNS) { s = p.replace(s, "[REDACTED]") }
        return if (body.length > MAX_ERROR_BODY_LEN) "$s...[truncated]" else s
    }

    private suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { this.cancel() }
        this.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!cont.isCompleted) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (!cont.isCompleted) cont.resume(response) }
        })
    }

    private fun parseTokenResponse(responseBody: String): TokenResponse {
        val jsonObj = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content
            ?: throw OAuthException(0, "No access_token in response", "Response keys: ${jsonObj.keys.joinToString()}")
        val refreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.content
        val idToken = jsonObj["id_token"]?.jsonPrimitive?.content
        val expiresIn = jsonObj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

        // Parse JWT exp claim for precise expiry tracking
        val expiry = JwtHelper.parseJwtClaim(accessToken, "exp")?.toLongOrNull()
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

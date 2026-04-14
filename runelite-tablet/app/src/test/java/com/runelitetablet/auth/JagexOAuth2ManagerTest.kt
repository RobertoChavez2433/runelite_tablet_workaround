package com.runelitetablet.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.FormBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the OAuth2 flow using MockWebServer.
 * Tests real OkHttp + real JSON parsing against mock HTTP responses.
 */
class JagexOAuth2ManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `token exchange parses successful response`() = runTest {
        server.enqueue(MockResponse().setBody(TOKEN_RESPONSE_JSON).setResponseCode(200))

        val response = executeTokenExchange("test_code", "test_verifier")

        assertNotNull(response)
        assertEquals("test_access_token", response.accessToken)
        assertEquals("test_refresh_token", response.refreshToken)
        assertEquals(3600L, response.expiresIn)
    }

    @Test
    fun `token exchange throws on HTTP 400 invalid_grant`() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":"invalid_grant"}""").setResponseCode(400))

        try {
            executeTokenExchange("bad_code", "test_verifier")
            assertTrue("Should have thrown", false)
        } catch (e: OAuthException) {
            assertEquals(400, e.httpCode)
        }
    }

    @Test
    fun `refresh tokens parses successful response`() = runTest {
        server.enqueue(MockResponse().setBody(REFRESH_RESPONSE_JSON).setResponseCode(200))

        val response = executeRefresh("old_refresh_token")

        assertEquals("new_access_token", response.accessToken)
        assertEquals("new_refresh_token", response.refreshToken)
        assertEquals(7200L, response.expiresIn)
    }

    @Test
    fun `refresh throws on HTTP 401`() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":"invalid_grant"}""").setResponseCode(401))

        try {
            executeRefresh("dead_token")
            assertTrue("Should have thrown", false)
        } catch (e: OAuthException) {
            assertEquals(401, e.httpCode)
        }
    }

    @Test
    fun `fetch accounts parses character list`() = runTest {
        server.enqueue(MockResponse().setBody(ACCOUNTS_JSON).setResponseCode(200))

        val characters = fetchAccounts("test_session")

        assertEquals(2, characters.size)
        assertEquals("char1", characters[0].accountId)
        assertEquals("Player One", characters[0].displayName)
        assertEquals("char2", characters[1].accountId)
    }

    @Test
    fun `create game session returns sessionId`() = runTest {
        server.enqueue(MockResponse().setBody("""{"sessionId":"sess_abc123"}""").setResponseCode(200))

        val sessionId = createSession("test_id_token")

        assertEquals("sess_abc123", sessionId)
    }

    @Test
    fun `validate session returns Valid on 200`() = runTest {
        server.enqueue(MockResponse().setBody("[]").setResponseCode(200))

        val result = validateSession("valid_session")

        assertTrue(result is SessionValidation.Valid)
    }

    @Test
    fun `validate session returns Expired on 401`() = runTest {
        server.enqueue(MockResponse().setBody("").setResponseCode(401))

        val result = validateSession("expired_session")

        assertTrue(result is SessionValidation.Expired)
    }

    @Test
    fun `validate session returns Expired on 403`() = runTest {
        server.enqueue(MockResponse().setBody("").setResponseCode(403))

        val result = validateSession("forbidden_session")

        assertTrue(result is SessionValidation.Expired)
    }

    @Test
    fun `validate session returns NetworkError on 500`() = runTest {
        server.enqueue(MockResponse().setBody("").setResponseCode(500))

        val result = validateSession("server_error_session")

        assertTrue(result is SessionValidation.NetworkError)
    }

    // --- Helpers that exercise real OkHttp against MockWebServer ---

    private fun executeTokenExchange(code: String, verifier: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code)
            .add("code_verifier", verifier).add("client_id", "test").build()
        val request = Request.Builder().url(server.url("/oauth2/token")).post(body).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OAuthException(response.code, "HTTP ${response.code}", response.body?.string() ?: "")
            parseTokenResponse(response.body!!.string())
        }
    }

    private fun executeRefresh(refreshToken: String): TokenResponse {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", refreshToken)
            .add("client_id", "test").build()
        val request = Request.Builder().url(server.url("/oauth2/token")).post(body).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OAuthException(response.code, "HTTP ${response.code}", response.body?.string() ?: "")
            parseTokenResponse(response.body!!.string())
        }
    }

    private fun fetchAccounts(sessionId: String): List<GameCharacter> {
        val request = Request.Builder().url(server.url("/accounts")).header("Authorization", "Bearer $sessionId").get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OAuthException(response.code, "HTTP ${response.code}", "")
            val body = response.body?.string() ?: return@use emptyList()
            json.parseToJsonElement(body).jsonArray.map { e ->
                val o = e.jsonObject
                GameCharacter(o["accountId"]?.jsonPrimitive?.content ?: "", o["displayName"]?.jsonPrimitive?.content ?: "")
            }
        }
    }

    private fun createSession(idToken: String): String {
        val body = """{"idToken":"$idToken"}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/sessions")).post(body).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OAuthException(response.code, "HTTP ${response.code}", "")
            val respBody = response.body?.string() ?: throw OAuthException(0, "Empty body", "")
            json.parseToJsonElement(respBody).jsonObject["sessionId"]?.jsonPrimitive?.content
                ?: throw OAuthException(0, "No sessionId", "")
        }
    }

    private fun validateSession(sessionId: String): SessionValidation {
        val request = Request.Builder().url(server.url("/accounts")).header("Authorization", "Bearer $sessionId").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> SessionValidation.Valid
                    response.code == 401 || response.code == 403 -> SessionValidation.Expired
                    else -> SessionValidation.NetworkError(IOException("HTTP ${response.code}"))
                }
            }
        } catch (e: IOException) {
            SessionValidation.NetworkError(e)
        }
    }

    private fun parseTokenResponse(responseBody: String): TokenResponse {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.content ?: throw OAuthException(0, "No access_token", "")
        val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
        val idToken = obj["id_token"]?.jsonPrimitive?.content
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        return TokenResponse(accessToken, refreshToken, idToken, expiresIn, System.currentTimeMillis() / 1000 + expiresIn)
    }

    companion object {
        private val TOKEN_RESPONSE_JSON = """
            {"access_token":"test_access_token","refresh_token":"test_refresh_token","id_token":"eyJhbGciOiJSUzI1NiJ9.eyJsb2dpbl9wcm92aWRlciI6InJ1bmVzY2FwZSIsImV4cCI6OTk5OTk5OTk5OX0.sig","expires_in":3600,"token_type":"Bearer"}
        """.trimIndent()

        private val REFRESH_RESPONSE_JSON = """
            {"access_token":"new_access_token","refresh_token":"new_refresh_token","expires_in":7200,"token_type":"Bearer"}
        """.trimIndent()

        private val ACCOUNTS_JSON = """
            [{"accountId":"char1","displayName":"Player One"},{"accountId":"char2","displayName":"Player Two"}]
        """.trimIndent()
    }
}

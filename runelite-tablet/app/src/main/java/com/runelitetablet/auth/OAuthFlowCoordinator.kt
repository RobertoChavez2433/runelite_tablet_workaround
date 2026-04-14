package com.runelitetablet.auth

import com.runelitetablet.domain.logging.Logger
import java.net.URLDecoder

/**
 * Pure Kotlin OAuth state machine for the 2-step Jagex flow.
 * Extracted from GeckoAuthActivity for testability.
 *
 * State transitions:
 *   LOADING_STEP1 -> AWAITING_STEP1_REDIRECT -> EXCHANGING_TOKENS
 *     -> (runescape) DONE_SUCCESS
 *     -> (jagex) LOADING_STEP2 -> AWAITING_STEP2_REDIRECT -> DONE_SUCCESS
 *   Any -> ERROR
 */
class OAuthFlowCoordinator(
    private val oauthManager: JagexOAuth2Manager,
    private val codeVerifier: String,
    private val expectedStep1State: String,
    private val expectedStep2State: String,
    private val expectedNonce: String,
    private val logger: Logger? = null
) {
    enum class State {
        LOADING_STEP1, AWAITING_STEP1_REDIRECT, EXCHANGING_TOKENS,
        LOADING_STEP2, AWAITING_STEP2_REDIRECT, DONE_SUCCESS, ERROR
    }

    var currentState = State.LOADING_STEP1
        private set

    private var step1AccessToken: String? = null
    private var step1RefreshToken: String? = null
    private var step1ExpiresIn: Long = 0L
    private var step1AccessTokenExpiry: Long = 0L

    sealed class Result {
        data class Success(
            val loginProvider: String,
            val accessToken: String?,
            val refreshToken: String?,
            val idToken: String?,
            val expiresIn: Long,
            val accessTokenExpiry: Long
        ) : Result()
        data class LoadStep2(val url: String) : Result()
        data class Error(val message: String) : Result()
    }

    private fun transition(newState: State, reason: String) {
        val oldState = currentState
        currentState = newState
        logger?.state("OAuthFlow: $oldState → $newState ($reason)")
    }

    fun parseStep1Redirect(uri: String): Step1ParseResult? {
        val redactedUri = uri.take(30) + if (uri.length > 30) "...[redacted]" else ""
        logger?.state("OAuthFlow.parseStep1Redirect: state=$currentState uri=$redactedUri")
        if (currentState != State.LOADING_STEP1 && currentState != State.AWAITING_STEP1_REDIRECT) {
            logger?.w("AUTH", "OAuthFlow.parseStep1Redirect: ignored, wrong state=$currentState")
            return null
        }
        transition(State.AWAITING_STEP1_REDIRECT, "step1 redirect received")

        val params = parseJagexUri(uri)
        logger?.d("AUTH", "OAuthFlow: parsed ${params.size} params, keys=${params.keys}")
        val code = params["code"] ?: run {
            transition(State.ERROR, "missing authorization code")
            return Step1ParseResult.Error("Invalid login redirect: missing authorization code")
        }
        val state = params["state"]
        if (state != expectedStep1State) {
            transition(State.ERROR, "state mismatch")
            return Step1ParseResult.Error("Security check failed: state mismatch")
        }
        logger?.d("AUTH", "OAuthFlow: step1 params validated, code present, state matched")

        transition(State.EXCHANGING_TOKENS, "step1 params valid")
        return Step1ParseResult.CodeCaptured(code)
    }

    suspend fun exchangeAndRoute(code: String, step2Url: String): Result {
        logger?.state("OAuthFlow.exchangeAndRoute: state=$currentState, exchanging code for tokens")
        val exchangeStartMs = System.currentTimeMillis()
        val tokenResponse = try {
            oauthManager.exchangeCodeForTokens(code, codeVerifier)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - exchangeStartMs
            logger?.e("AUTH", "OAuthFlow.exchangeAndRoute: token exchange failed after ${elapsed}ms: ${e.message}")
            transition(State.ERROR, "token exchange failed: ${e.message}")
            return Result.Error("Token exchange failed: ${e.message}")
        }
        val exchangeMs = System.currentTimeMillis() - exchangeStartMs
        if (exchangeMs > 10_000) logger?.w("AUTH", "OAuthFlow: token exchange slow (${exchangeMs}ms) — possible timeout risk")
        logger?.d("AUTH", "OAuthFlow: token exchange complete, hasIdToken=${tokenResponse.idToken != null}, " +
            "hasAccessToken=${tokenResponse.accessToken != null}, expiresIn=${tokenResponse.expiresIn}")
        val idToken = tokenResponse.idToken
        val loginProvider = if (idToken != null) oauthManager.parseLoginProvider(idToken) else "jagex"
        logger?.d("AUTH", "OAuthFlow: loginProvider=$loginProvider")

        return when (loginProvider) {
            "runescape" -> {
                transition(State.DONE_SUCCESS, "runescape login, single-step complete")
                logger?.state("OAuthFlow: tokens handed off to credential store (provider=runescape)")
                Result.Success(loginProvider, tokenResponse.accessToken, tokenResponse.refreshToken,
                    tokenResponse.idToken, tokenResponse.expiresIn, tokenResponse.accessTokenExpiry)
            }
            else -> {
                step1AccessToken = tokenResponse.accessToken
                step1RefreshToken = tokenResponse.refreshToken
                step1ExpiresIn = tokenResponse.expiresIn
                step1AccessTokenExpiry = tokenResponse.accessTokenExpiry
                transition(State.LOADING_STEP2, "jagex login, need step2 consent")
                Result.LoadStep2(step2Url)
            }
        }
    }

    fun parseStep2Redirect(uri: String): Result {
        val redactedUri = uri.substringBefore("#") + "#[redacted]"
        logger?.state("OAuthFlow.parseStep2Redirect: state=$currentState uri=$redactedUri")
        if (currentState != State.LOADING_STEP2 && currentState != State.AWAITING_STEP2_REDIRECT) {
            logger?.w("AUTH", "OAuthFlow.parseStep2Redirect: ignored, wrong state=$currentState")
            return Result.Error("Invalid state for Step 2 redirect: $currentState")
        }
        transition(State.AWAITING_STEP2_REDIRECT, "step2 redirect received")

        val fragment = uri.substringAfter("#", "")
        if (fragment.isEmpty()) {
            transition(State.ERROR, "fragment not available")
            return Result.Error("Could not capture consent token: fragment not available")
        }

        val params = parseFragmentParams(fragment)
        logger?.d("AUTH", "OAuthFlow: parsed ${params.size} fragment params, keys=${params.keys}")
        val idToken = params["id_token"]
        val state = params["state"]

        if (idToken == null) {
            transition(State.ERROR, "missing id_token")
            return Result.Error("Could not capture consent token: missing id_token")
        }
        if (state != expectedStep2State) {
            transition(State.ERROR, "consent state mismatch")
            return Result.Error("Security check failed: consent state mismatch")
        }
        if (!oauthManager.verifyNonce(idToken, expectedNonce)) {
            transition(State.ERROR, "nonce mismatch")
            return Result.Error("Security check failed: nonce mismatch")
        }
        logger?.d("AUTH", "OAuthFlow: step2 params validated, id_token present, state+nonce matched")

        transition(State.DONE_SUCCESS, "step2 consent complete")
        logger?.state("OAuthFlow: tokens handed off to credential store (provider=jagex)")
        return Result.Success("jagex", step1AccessToken, step1RefreshToken,
            idToken, step1ExpiresIn, step1AccessTokenExpiry)
    }

    sealed class Step1ParseResult {
        data class CodeCaptured(val code: String) : Step1ParseResult()
        data class Error(val message: String) : Step1ParseResult()
    }

    companion object {
        fun parseJagexUri(uri: String): Map<String, String> {
            val paramsStr = uri.removePrefix("jagex:")
            if (paramsStr.isBlank()) return emptyMap()
            return paramsStr.split(",", "&").mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    try { URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") }
                    catch (_: Exception) { null }
                } else null
            }.toMap()
        }

        fun parseFragmentParams(fragment: String): Map<String, String> {
            if (fragment.isBlank()) return emptyMap()
            return fragment.split("&").mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    try { URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") }
                    catch (_: Exception) { null }
                } else null
            }.toMap()
        }
    }
}

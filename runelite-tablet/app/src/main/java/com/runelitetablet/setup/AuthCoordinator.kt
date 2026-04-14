package com.runelitetablet.setup

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResult
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.domain.logging.CorrelationId
import com.runelitetablet.auth.GeckoAuthActivity
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.auth.GameCharacter
import com.runelitetablet.auth.OAuthException
import com.runelitetablet.auth.PkceHelper
import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AuthCoordinator(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val oAuth2Manager: JagexOAuth2Manager,
    private val orchestrator: SetupOrchestrator,
    private val scope: CoroutineScope,
    private val onScreenChange: (AppScreen) -> Unit,
    private val onPerformLaunch: suspend () -> Unit,
    private val logger: Logger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Volatile private var pendingSessionId: String? = null
    val pendingLaunchAfterAuth = AtomicBoolean(false)
    @Volatile private var pendingNonce: String? = null

    fun clearState() { pendingSessionId = null; pendingNonce = null; pendingLaunchAfterAuth.set(false) }

    fun getDisplayName(): String? = credentialStore.getDisplayName()

    fun startLogin() {
        val corrId = CorrelationId.generate("auth")
        val actions = orchestrator.actions
        if (actions == null) {
            logger?.e("AUTH", "startLogin: no actions bound", correlationId = corrId)
            onScreenChange(AppScreen.AuthError("Activity not available"))
            return
        }

        val codeVerifier = PkceHelper.generateVerifier()
        val step1State = PkceHelper.generateState()
        val step2State = PkceHelper.generateState()
        val nonce = oAuth2Manager.generateNonce()
        pendingNonce = nonce

        val step1Url = oAuth2Manager.buildStep1AuthUrl(codeVerifier, step1State).toString()
        val step2Url = oAuth2Manager.buildStep2ConsentUrl(step2State, nonce).toString()

        val intent = GeckoAuthActivity.createIntent(
            context, step1Url, step2Url, codeVerifier,
            step1State, step2State, nonce
        )
        logger?.step("auth", "startLogin: launching GeckoAuthActivity", correlationId = corrId)
        actions.launchAuthActivity(intent)
    }

    fun handleAuthResult(result: ActivityResult, correlationId: String? = null) {
        logger?.state("AuthCoordinator.handleAuthResult: resultCode=${result.resultCode}", correlationId = correlationId)
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val data = result.data ?: run {
                    onScreenChange(AppScreen.AuthError("Authentication failed: no data received"))
                    return
                }
                val loginProvider = data.getStringExtra(GeckoAuthActivity.RESULT_LOGIN_PROVIDER) ?: "jagex"
                logger?.step("auth", "handleAuthResult: loginProvider=$loginProvider hasAccessToken=${data.getStringExtra(GeckoAuthActivity.RESULT_ACCESS_TOKEN) != null} hasIdToken=${data.getStringExtra(GeckoAuthActivity.RESULT_ID_TOKEN) != null}", correlationId = correlationId)
                val accessToken = data.getStringExtra(GeckoAuthActivity.RESULT_ACCESS_TOKEN)
                val refreshToken = data.getStringExtra(GeckoAuthActivity.RESULT_REFRESH_TOKEN)
                val idToken = data.getStringExtra(GeckoAuthActivity.RESULT_ID_TOKEN)
                val accessTokenExpiry = data.getLongExtra(GeckoAuthActivity.RESULT_ACCESS_TOKEN_EXPIRY, 0L)

                scope.launch {
                    try {
                        if (accessToken != null) {
                            withContext(ioDispatcher) {
                                credentialStore.storeTokens(accessToken, refreshToken, accessTokenExpiry)
                            }
                        }

                        when (loginProvider) {
                            "runescape" -> navigateToLaunchOrResume()
                            else -> {
                                if (idToken == null) {
                                    onScreenChange(AppScreen.AuthError("No consent token received"))
                                    return@launch
                                }
                                runStep3GameSession(idToken)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: OAuthException) {
                        pendingLaunchAfterAuth.set(false)
                        onScreenChange(AppScreen.AuthError(e.message ?: "Authentication failed"))
                    } catch (e: Exception) {
                        pendingLaunchAfterAuth.set(false)
                        onScreenChange(AppScreen.AuthError(e.message ?: "Login failed"))
                    } finally { pendingNonce = null }
                }
            }
            Activity.RESULT_CANCELED -> {
                pendingLaunchAfterAuth.set(false)
                onScreenChange(AppScreen.AuthError("Login cancelled — tap Sign In to try again"))
                pendingNonce = null
            }
            else -> {
                val error = result.data?.getStringExtra(GeckoAuthActivity.RESULT_ERROR) ?: "Login failed"
                pendingLaunchAfterAuth.set(false)
                onScreenChange(AppScreen.AuthError(error))
                pendingNonce = null
            }
        }
    }

    private suspend fun runStep3GameSession(idToken: String) {
        val sessionId = oAuth2Manager.createGameSession(idToken)
        val characters = oAuth2Manager.fetchAccounts(sessionId)

        if (characters.isEmpty()) {
            onScreenChange(AppScreen.AuthError("No characters found on this account"))
            return
        }

        if (characters.size == 1) {
            val character = characters[0]
            withContext(ioDispatcher) {
                credentialStore.storeGameSession(sessionId, character.accountId, character.displayName)
            }
            navigateToLaunchOrResume()
        } else {
            pendingSessionId = sessionId
            onScreenChange(AppScreen.CharacterSelect(characters))
        }
    }

    fun onCharacterSelected(character: GameCharacter) {
        scope.launch {
            try {
                val sessionId = pendingSessionId ?: run {
                    onScreenChange(AppScreen.AuthError("Session expired — please log in again"))
                    return@launch
                }
                withContext(ioDispatcher) {
                    credentialStore.storeGameSession(sessionId, character.accountId, character.displayName)
                }
                pendingSessionId = null
                navigateToLaunchOrResume()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                onScreenChange(AppScreen.AuthError(e.message ?: "Session creation failed"))
            }
        }
    }

    fun skipLogin() { onScreenChange(AppScreen.Launch) }

    suspend fun navigateToLaunchOrResume(correlationId: String? = null) {
        if (pendingLaunchAfterAuth.compareAndSet(true, false)) {
            logger?.step("auth", "navigateToLaunchOrResume: auto-resuming launch", correlationId = correlationId)
            onPerformLaunch()
        } else {
            logger?.step("auth", "navigateToLaunchOrResume: showing launch screen", correlationId = correlationId)
            onScreenChange(AppScreen.Launch)
        }
    }

    suspend fun refreshIfNeeded(correlationId: String? = null): AuthResult {
        val corrId = if (correlationId != null) CorrelationId.nested(correlationId, "refresh") else CorrelationId.generate("refresh")
        logger?.step("auth", "refreshIfNeeded: checking token expiry parentCorrId=$correlationId", correlationId = corrId)
        val expiry = withContext(ioDispatcher) { credentialStore.getAccessTokenExpiry() }
        val now = System.currentTimeMillis() / 1000L
        if (now < expiry - 60) { logger?.step("auth", "refreshIfNeeded: token still valid ttl=${expiry - now}s", correlationId = corrId); return AuthResult.Valid }

        val refreshToken = withContext(ioDispatcher) { credentialStore.getRefreshToken() }
            ?: run { logger?.w("AUTH", "refreshIfNeeded: no refresh token, needs login", correlationId = corrId); return AuthResult.NeedsLogin }

        return performTokenRefresh(refreshToken, corrId)
    }

    private suspend fun performTokenRefresh(refreshToken: String, correlationId: String? = null): AuthResult {
        return try {
            logger?.step("auth", "performTokenRefresh: exchanging refresh token", correlationId = correlationId)
            val response = oAuth2Manager.refreshTokens(refreshToken)
            withContext(ioDispatcher) {
                credentialStore.storeTokens(response.accessToken, response.refreshToken, response.accessTokenExpiry)
            }
            logger?.step("auth", "performTokenRefresh: tokens refreshed successfully", correlationId = correlationId)
            AuthResult.Refreshed
        } catch (e: OAuthException) {
            logger?.w("AUTH", "performTokenRefresh: OAuthException httpCode=${e.httpCode}", correlationId = correlationId)
            if (e.httpCode == 401 || e.httpCode == 400) AuthResult.NeedsLogin
            else AuthResult.NetworkError(e)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { logger?.e("AUTH", "performTokenRefresh: unexpected error", throwable = e, correlationId = correlationId); AuthResult.NetworkError(e) }
    }

    fun signOut() {
        credentialStore.clearCredentials()
        onScreenChange(AppScreen.Login)
    }
}

package com.runelitetablet.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class GeckoAuthActivity : ComponentActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var coordinator: OAuthFlowCoordinator
    private lateinit var step2Url: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.lifecycle("GeckoAuthActivity.onCreate")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        if (savedInstanceState != null) {
            AppLog.w("AUTH", "GeckoAuthActivity: restored after process death — cancelling auth")
            finishWithError("Authentication was interrupted. Please try again.")
            return
        }

        val step1Url = intent.getStringExtra(EXTRA_STEP1_URL) ?: return finishWithError("Missing step1_url")
        step2Url = intent.getStringExtra(EXTRA_STEP2_URL) ?: return finishWithError("Missing step2_url")
        val codeVerifier = intent.getStringExtra(EXTRA_CODE_VERIFIER) ?: return finishWithError("Missing code_verifier")
        val step1State = intent.getStringExtra(EXTRA_STEP1_STATE) ?: return finishWithError("Missing step1_state")
        val step2State = intent.getStringExtra(EXTRA_STEP2_STATE) ?: return finishWithError("Missing step2_state")
        val nonce = intent.getStringExtra(EXTRA_NONCE) ?: return finishWithError("Missing nonce")

        val oauthManager = JagexOAuth2Manager((application as RuneLiteTabletApp).httpClient)
        coordinator = OAuthFlowCoordinator(oauthManager, codeVerifier, step1State, step2State, nonce)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(Activity.RESULT_CANCELED); finish()
            }
        })

        try {
            val runtime = GeckoAuthRuntime.getOrCreate(this)
            geckoView = GeckoView(this)
            setContentView(geckoView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            session = GeckoSession()
            session.navigationDelegate = createNavigationDelegate()
            session.open(runtime)
            geckoView.setSession(session)
            session.loadUri(step1Url)
        } catch (e: Exception) {
            AppLog.e("AUTH", "GeckoAuthActivity: failed to init GeckoView: ${e.message}", e)
            finishWithError("Failed to initialize browser: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized) { try { session.close() } catch (_: Exception) {} }
    }

    private fun createNavigationDelegate() = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            return when {
                uri.startsWith("jagex:") -> { handleStep1Redirect(uri); GeckoResult.fromValue(AllowOrDeny.DENY) }
                uri.startsWith("http://localhost") -> { handleStep2Redirect(uri); GeckoResult.fromValue(AllowOrDeny.DENY) }
                else -> GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }
    }

    private fun handleStep1Redirect(uri: String) {
        val parseResult = coordinator.parseStep1Redirect(uri) ?: return
        when (parseResult) {
            is OAuthFlowCoordinator.Step1ParseResult.Error -> finishWithError(parseResult.message)
            is OAuthFlowCoordinator.Step1ParseResult.CodeCaptured -> {
                lifecycleScope.launch {
                    try {
                        when (val result = coordinator.exchangeAndRoute(parseResult.code, step2Url)) {
                            is OAuthFlowCoordinator.Result.Success -> finishWithSuccess(result)
                            is OAuthFlowCoordinator.Result.LoadStep2 -> withContext(Dispatchers.Main) { session.loadUri(result.url) }
                            is OAuthFlowCoordinator.Result.Error -> finishWithError(result.message)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: OAuthException) { finishWithError("Token exchange failed: ${e.message}") }
                    catch (e: Exception) { finishWithError("Authentication error: ${e.message}") }
                }
            }
        }
    }

    private fun handleStep2Redirect(uri: String) {
        when (val result = coordinator.parseStep2Redirect(uri)) {
            is OAuthFlowCoordinator.Result.Success -> finishWithSuccess(result)
            is OAuthFlowCoordinator.Result.Error -> finishWithError(result.message)
            is OAuthFlowCoordinator.Result.LoadStep2 -> {} // shouldn't happen
        }
    }

    private fun finishWithSuccess(result: OAuthFlowCoordinator.Result.Success) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(RESULT_LOGIN_PROVIDER, result.loginProvider)
            result.accessToken?.let { putExtra(RESULT_ACCESS_TOKEN, it) }
            result.refreshToken?.let { putExtra(RESULT_REFRESH_TOKEN, it) }
            result.idToken?.let { putExtra(RESULT_ID_TOKEN, it) }
            putExtra(RESULT_EXPIRES_IN, result.expiresIn)
            putExtra(RESULT_ACCESS_TOKEN_EXPIRY, result.accessTokenExpiry)
        })
        finish()
    }

    private fun finishWithError(message: String) {
        AppLog.e("AUTH", "GeckoAuthActivity: finishing with error: $message")
        setResult(Activity.RESULT_FIRST_USER, Intent().apply { putExtra(RESULT_ERROR, message) })
        finish()
    }

    companion object {
        const val EXTRA_STEP1_URL = "step1_url"
        const val EXTRA_STEP2_URL = "step2_url"
        const val EXTRA_CODE_VERIFIER = "code_verifier"
        const val EXTRA_STEP1_STATE = "step1_state"
        const val EXTRA_STEP2_STATE = "step2_state"
        const val EXTRA_NONCE = "nonce"
        const val RESULT_LOGIN_PROVIDER = "login_provider"
        const val RESULT_ACCESS_TOKEN = "access_token"
        const val RESULT_REFRESH_TOKEN = "refresh_token"
        const val RESULT_ID_TOKEN = "id_token"
        const val RESULT_EXPIRES_IN = "expires_in"
        const val RESULT_ACCESS_TOKEN_EXPIRY = "access_token_expiry"
        const val RESULT_ERROR = "error"

        fun createIntent(
            context: Context, step1Url: String, step2Url: String,
            codeVerifier: String, step1State: String, step2State: String, nonce: String
        ): Intent = Intent(context, GeckoAuthActivity::class.java).apply {
            putExtra(EXTRA_STEP1_URL, step1Url)
            putExtra(EXTRA_STEP2_URL, step2Url)
            putExtra(EXTRA_CODE_VERIFIER, codeVerifier)
            putExtra(EXTRA_STEP1_STATE, step1State)
            putExtra(EXTRA_STEP2_STATE, step2State)
            putExtra(EXTRA_NONCE, nonce)
        }
    }
}

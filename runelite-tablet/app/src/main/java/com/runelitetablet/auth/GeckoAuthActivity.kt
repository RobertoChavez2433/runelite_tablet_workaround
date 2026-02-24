package com.runelitetablet.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.runelitetablet.BuildConfig
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URLDecoder

/**
 * Dedicated Activity for Jagex OAuth2 authentication using GeckoView (Firefox engine).
 *
 * GeckoView passes Cloudflare challenges (unlike Android WebView) and can intercept
 * redirects at the engine level via NavigationDelegate.onLoadRequest(), solving the
 * port 80 blocker for Step 2 consent (http://localhost redirect).
 *
 * Handles both Step 1 (account auth) and Step 2 (consent) in a single GeckoView session.
 * Returns tokens via ActivityResult to the calling Activity.
 *
 * State Machine:
 *   LOADING_STEP1 -> AWAITING_STEP1_REDIRECT -> EXCHANGING_TOKENS
 *     -> (if runescape) DONE_SUCCESS
 *     -> (if jagex) LOADING_STEP2 -> AWAITING_STEP2_REDIRECT -> DONE_SUCCESS
 *   Any state -> ERROR / CANCELLED
 */
class GeckoAuthActivity : ComponentActivity() {

    companion object {
        // Input extras
        const val EXTRA_STEP1_URL = "step1_url"
        const val EXTRA_STEP2_URL = "step2_url"
        const val EXTRA_CODE_VERIFIER = "code_verifier"
        const val EXTRA_STEP1_STATE = "step1_state"
        const val EXTRA_STEP2_STATE = "step2_state"
        const val EXTRA_NONCE = "nonce"

        // Output extras (RESULT_OK)
        const val RESULT_LOGIN_PROVIDER = "login_provider"
        const val RESULT_ACCESS_TOKEN = "access_token"
        const val RESULT_REFRESH_TOKEN = "refresh_token"
        const val RESULT_ID_TOKEN = "id_token"
        const val RESULT_EXPIRES_IN = "expires_in"
        const val RESULT_ACCESS_TOKEN_EXPIRY = "access_token_expiry"

        // Output extras (error case — RESULT_FIRST_USER)
        const val RESULT_ERROR = "error"

        // GeckoRuntime singleton — must be one per process, never shut down.
        // @Synchronized provides both visibility and atomicity for the check-then-create pattern.
        @Volatile private var sRuntime: GeckoRuntime? = null

        @Synchronized
        fun getOrCreateRuntime(context: Context): GeckoRuntime {
            return sRuntime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .consoleOutput(BuildConfig.DEBUG)
                    .build()
            ).also { sRuntime = it }
        }

        /**
         * Create an Intent to launch GeckoAuthActivity with the required parameters.
         */
        fun createIntent(
            context: Context,
            step1Url: String,
            step2Url: String,
            codeVerifier: String,
            step1State: String,
            step2State: String,
            nonce: String
        ): Intent {
            return Intent(context, GeckoAuthActivity::class.java).apply {
                putExtra(EXTRA_STEP1_URL, step1Url)
                putExtra(EXTRA_STEP2_URL, step2Url)
                putExtra(EXTRA_CODE_VERIFIER, codeVerifier)
                putExtra(EXTRA_STEP1_STATE, step1State)
                putExtra(EXTRA_STEP2_STATE, step2State)
                putExtra(EXTRA_NONCE, nonce)
            }
        }
    }

    // Internal state machine
    private enum class AuthState {
        LOADING_STEP1,
        AWAITING_STEP1_REDIRECT,
        EXCHANGING_TOKENS,
        LOADING_STEP2,
        AWAITING_STEP2_REDIRECT,
        DONE_SUCCESS,
        ERROR,
        CANCELLED
    }

    private var currentState = AuthState.LOADING_STEP1

    // Input parameters (extracted from intent)
    private lateinit var step1Url: String
    private lateinit var step2Url: String
    private lateinit var codeVerifier: String
    private lateinit var expectedStep1State: String
    private lateinit var expectedStep2State: String
    private lateinit var expectedNonce: String

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var oauthManager: JagexOAuth2Manager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.lifecycle("GeckoAuthActivity.onCreate")

        // Extract required parameters from intent
        step1Url = intent.getStringExtra(EXTRA_STEP1_URL)
            ?: return finishWithError("Missing step1_url")
        step2Url = intent.getStringExtra(EXTRA_STEP2_URL)
            ?: return finishWithError("Missing step2_url")
        codeVerifier = intent.getStringExtra(EXTRA_CODE_VERIFIER)
            ?: return finishWithError("Missing code_verifier")
        expectedStep1State = intent.getStringExtra(EXTRA_STEP1_STATE)
            ?: return finishWithError("Missing step1_state")
        expectedStep2State = intent.getStringExtra(EXTRA_STEP2_STATE)
            ?: return finishWithError("Missing step2_state")
        expectedNonce = intent.getStringExtra(EXTRA_NONCE)
            ?: return finishWithError("Missing nonce")

        // Use the application-level OkHttpClient for token exchange (shared connection pool)
        oauthManager = JagexOAuth2Manager((application as RuneLiteTabletApp).httpClient)

        // Handle back press -> cancel auth
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AppLog.step("auth", "GeckoAuthActivity: back pressed — cancelling")
                currentState = AuthState.CANCELLED
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        })

        // Initialize GeckoView
        try {
            val runtime = getOrCreateRuntime(this)

            geckoView = GeckoView(this)
            setContentView(
                geckoView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            session = GeckoSession()
            session.navigationDelegate = createNavigationDelegate()
            session.open(runtime)
            geckoView.setSession(session)

            // Load Step 1 auth URL
            currentState = AuthState.LOADING_STEP1
            AppLog.step("auth", "GeckoAuthActivity: loading Step 1 auth URL")
            session.loadUri(step1Url)

        } catch (e: Exception) {
            AppLog.e("AUTH", "GeckoAuthActivity: failed to init GeckoView: ${e.message}", e)
            finishWithError("Failed to initialize browser: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.lifecycle("GeckoAuthActivity.onDestroy")
        if (::session.isInitialized) {
            session.close()
        }
    }

    // -------------------------------------------------------------------------
    // NavigationDelegate — redirect interception
    // -------------------------------------------------------------------------

    private fun createNavigationDelegate(): GeckoSession.NavigationDelegate {
        return object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                AppLog.step("auth", "GeckoAuthActivity.onLoadRequest: uri=${sanitizeUrl(uri)}")

                return when {
                    // Step 1: jagex: scheme redirect from launcher-redirect page
                    uri.startsWith("jagex:") -> {
                        AppLog.step("auth", "GeckoAuthActivity: intercepted jagex: redirect")
                        handleStep1Redirect(uri)
                        GeckoResult.fromValue(AllowOrDeny.DENY)
                    }

                    // Step 2: http://localhost redirect with fragment
                    uri.startsWith("http://localhost") -> {
                        AppLog.step("auth", "GeckoAuthActivity: intercepted localhost redirect (hasFragment=${uri.contains("#")})")
                        handleStep2Redirect(uri)
                        GeckoResult.fromValue(AllowOrDeny.DENY)
                    }

                    // Everything else: allow navigation
                    else -> GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                AppLog.step("auth", "GeckoAuthActivity.onLocationChange: url=${sanitizeUrl(url ?: "null")}")
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {}
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {}
        }
    }

    // -------------------------------------------------------------------------
    // Step 1: jagex: scheme redirect handling
    // -------------------------------------------------------------------------

    private fun handleStep1Redirect(uri: String) {
        if (currentState != AuthState.LOADING_STEP1 && currentState != AuthState.AWAITING_STEP1_REDIRECT) {
            AppLog.w("AUTH", "GeckoAuthActivity: ignoring jagex: redirect in state $currentState")
            return
        }
        currentState = AuthState.AWAITING_STEP1_REDIRECT

        val params = parseJagexUri(uri)
        val code = params["code"]
        val state = params["state"]

        if (code == null) {
            finishWithError("Invalid login redirect: missing authorization code")
            return
        }
        if (state != expectedStep1State) {
            finishWithError("Security check failed: state mismatch")
            return
        }

        // Exchange code for tokens
        currentState = AuthState.EXCHANGING_TOKENS
        AppLog.step("auth", "GeckoAuthActivity: Step 1 code captured, exchanging for tokens")

        lifecycleScope.launch {
            try {
                val tokenResponse = oauthManager.exchangeCodeForTokens(code, codeVerifier)
                AppLog.step("auth", "GeckoAuthActivity: token exchange succeeded")

                // Parse login_provider from id_token
                val idToken = tokenResponse.idToken
                val loginProvider = if (idToken != null) {
                    oauthManager.parseLoginProvider(idToken)
                } else {
                    "jagex" // Default if no id_token
                }
                AppLog.step("auth", "GeckoAuthActivity: login_provider=$loginProvider")

                when (loginProvider) {
                    "runescape" -> {
                        // Legacy RuneScape account — done after Step 1
                        AppLog.step("auth", "GeckoAuthActivity: RuneScape account — completing with Step 1 tokens")
                        finishWithSuccess(
                            loginProvider = loginProvider,
                            accessToken = tokenResponse.accessToken,
                            refreshToken = tokenResponse.refreshToken,
                            idToken = tokenResponse.idToken,
                            expiresIn = tokenResponse.expiresIn,
                            accessTokenExpiry = tokenResponse.accessTokenExpiry
                        )
                    }
                    else -> {
                        // Jagex account — proceed to Step 2 consent
                        AppLog.step("auth", "GeckoAuthActivity: Jagex account — loading Step 2 consent URL")
                        currentState = AuthState.LOADING_STEP2
                        withContext(Dispatchers.Main) {
                            session.loadUri(step2Url)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OAuthException) {
                AppLog.e("AUTH", "GeckoAuthActivity: token exchange failed: ${e.message}", e)
                finishWithError("Token exchange failed: ${e.message}")
            } catch (e: Exception) {
                AppLog.e("AUTH", "GeckoAuthActivity: token exchange exception: ${e.message}", e)
                finishWithError("Authentication error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 2: http://localhost redirect handling
    // -------------------------------------------------------------------------

    private fun handleStep2Redirect(uri: String) {
        if (currentState != AuthState.LOADING_STEP2 && currentState != AuthState.AWAITING_STEP2_REDIRECT) {
            AppLog.w("AUTH", "GeckoAuthActivity: ignoring localhost redirect in state $currentState")
            return
        }
        currentState = AuthState.AWAITING_STEP2_REDIRECT

        // Extract fragment params: http://localhost#id_token=XXX&code=YYY&state=ZZZ
        val fragment = uri.substringAfter("#", "")
        if (fragment.isEmpty()) {
            // Fragment not captured in onLoadRequest — try contingency
            AppLog.w("AUTH", "GeckoAuthActivity: no fragment in localhost redirect — attempting JS extraction")
            attemptFragmentExtraction()
            return
        }

        val params = parseFragmentParams(fragment)
        val idToken = params["id_token"]
        val state = params["state"]

        if (idToken == null) {
            finishWithError("Could not capture consent token: missing id_token")
            return
        }
        if (state != expectedStep2State) {
            finishWithError("Security check failed: consent state mismatch")
            return
        }

        // Verify nonce in the consent id_token
        if (!oauthManager.verifyNonce(idToken, expectedNonce)) {
            finishWithError("Security check failed: nonce mismatch")
            return
        }

        AppLog.step("auth", "GeckoAuthActivity: Step 2 consent id_token captured and verified")
        finishWithSuccess(
            loginProvider = "jagex",
            accessToken = null,
            refreshToken = null,
            idToken = idToken,
            expiresIn = 0L,
            accessTokenExpiry = 0L
        )
    }

    /**
     * Contingency: if onLoadRequest() did not include the fragment.
     * GeckoView does not expose a direct JS evaluation API on GeckoSession,
     * so we cannot extract the fragment via JavaScript in this version.
     * Log the failure and return an error — on-device testing will determine
     * if this contingency is ever hit (onLoadRequest should include fragment).
     */
    private fun attemptFragmentExtraction() {
        AppLog.w("AUTH", "GeckoAuthActivity: fragment not in onLoadRequest — JS extraction not available in GeckoView")
        finishWithError("Could not capture consent token: fragment not available in redirect. Please try again.")
    }

    // -------------------------------------------------------------------------
    // URI Parsing
    // -------------------------------------------------------------------------

    /**
     * Parse jagex: URI — handles jagex:code=XXX,state=YYY,intent=social_auth
     * Splits on both ',' and '&' (Jagex uses comma separators, be defensive for both).
     * URL-decodes keys and values.
     */
    private fun parseJagexUri(uri: String): Map<String, String> {
        val paramsStr = uri.removePrefix("jagex:")
        if (paramsStr.isBlank()) return emptyMap()
        return paramsStr.split(",", "&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                try {
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }.toMap()
    }

    /**
     * Parse standard fragment params: id_token=XXX&code=YYY&state=ZZZ
     * Standard '&'-separated key=value pairs, URL-decoded.
     */
    private fun parseFragmentParams(fragment: String): Map<String, String> {
        if (fragment.isBlank()) return emptyMap()
        return fragment.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                try {
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }.toMap()
    }

    // -------------------------------------------------------------------------
    // Result helpers
    // -------------------------------------------------------------------------

    private fun finishWithSuccess(
        loginProvider: String,
        accessToken: String?,
        refreshToken: String?,
        idToken: String?,
        expiresIn: Long,
        accessTokenExpiry: Long
    ) {
        currentState = AuthState.DONE_SUCCESS
        val resultIntent = Intent().apply {
            putExtra(RESULT_LOGIN_PROVIDER, loginProvider)
            accessToken?.let { putExtra(RESULT_ACCESS_TOKEN, it) }
            refreshToken?.let { putExtra(RESULT_REFRESH_TOKEN, it) }
            idToken?.let { putExtra(RESULT_ID_TOKEN, it) }
            putExtra(RESULT_EXPIRES_IN, expiresIn)
            putExtra(RESULT_ACCESS_TOKEN_EXPIRY, accessTokenExpiry)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithError(message: String) {
        currentState = AuthState.ERROR
        AppLog.e("AUTH", "GeckoAuthActivity: finishing with error: $message")
        val resultIntent = Intent().apply {
            putExtra(RESULT_ERROR, message)
        }
        setResult(Activity.RESULT_FIRST_USER, resultIntent)
        finish()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sanitize a URL for logging — strip query parameters to avoid leaking tokens.
     */
    private fun sanitizeUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val fragmentIndex = url.indexOf('#')
        val endIndex = when {
            queryIndex >= 0 && fragmentIndex >= 0 -> minOf(queryIndex, fragmentIndex)
            queryIndex >= 0 -> queryIndex
            fragmentIndex >= 0 -> fragmentIndex
            else -> url.length
        }
        val hasQuery = queryIndex >= 0
        val hasFragment = fragmentIndex >= 0
        return url.substring(0, endIndex) +
            (if (hasQuery) "?[REDACTED]" else "") +
            (if (hasFragment) "#[REDACTED]" else "")
    }

}

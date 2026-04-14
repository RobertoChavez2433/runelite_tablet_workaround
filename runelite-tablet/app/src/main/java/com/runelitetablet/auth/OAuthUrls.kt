package com.runelitetablet.auth

import android.net.Uri
import com.runelitetablet.logging.AppLog

/**
 * Pure functions that build OAuth2 authorization URLs for the Jagex 2-step flow.
 */
object OAuthUrls {

    // OAuth2 authorization endpoint
    private const val AUTH_ENDPOINT = "https://account.jagex.com/oauth2/auth"

    // Step 1: Launcher client — account authentication
    private const val LAUNCHER_CLIENT_ID = "com_jagex_auth_desktop_launcher"
    private const val LAUNCHER_REDIRECT_URI = "https://secure.runescape.com/m=weblogin/launcher-redirect"
    private const val LAUNCHER_SCOPES = "openid offline gamesso.token.create user.profile.read"

    // Step 2: Consent client — Jagex account consent
    private const val CONSENT_CLIENT_ID = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2"
    private const val CONSENT_SCOPES = "openid offline"

    /**
     * Build the Step 1 authorization URL for Chrome Custom Tab.
     * Uses the launcher client with PKCE.
     */
    fun buildStep1AuthUrl(codeVerifier: String, state: String): Uri {
        AppLog.d("AUTH", "OAuthUrls.buildStep1AuthUrl: endpoint=$AUTH_ENDPOINT client=$LAUNCHER_CLIENT_ID params=7 redirect=$LAUNCHER_REDIRECT_URI")
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

    /**
     * Build the Step 2 consent URL for Chrome Custom Tab.
     * Uses the consent client with hybrid response_type (id_token code).
     */
    fun buildStep2ConsentUrl(state: String, nonce: String): Uri {
        AppLog.d("AUTH", "OAuthUrls.buildStep2ConsentUrl: endpoint=$AUTH_ENDPOINT client=$CONSENT_CLIENT_ID params=6 redirect=http://localhost")
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CONSENT_CLIENT_ID)
            .appendQueryParameter("response_type", "id_token code")
            .appendQueryParameter("redirect_uri", "http://localhost")
            .appendQueryParameter("scope", CONSENT_SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .build()
    }
}

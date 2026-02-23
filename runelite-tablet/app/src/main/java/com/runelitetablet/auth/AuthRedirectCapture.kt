package com.runelitetablet.auth

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Option 2: Intercept the documented Jagex redirect URI via CustomTabsCallback URL-change detection.
 *
 * When the Jagex OAuth2 server redirects to secure.runescape.com/m=weblogin/launcher-redirect?code=...&state=...,
 * the Chrome Custom Tab fires a navigation event. We detect the redirect URL in the callback,
 * extract the auth code and state, and complete the deferred.
 *
 * IMPORTANT: CustomTabsCallback.onNavigationEvent with NAVIGATION_STARTED fires on URL changes.
 * The redirect URL is available via the extras Bundle with key "android.support.customtabs.extra.URL".
 */
class CustomTabAuthCapture {

    companion object {
        private const val REDIRECT_HOST = "secure.runescape.com"
        private const val REDIRECT_PATH = "/m=weblogin/launcher-redirect"
        private const val EXTRA_URL = "android.support.customtabs.extra.URL"
        private const val NAVIGATION_STARTED = 1
    }

    private var serviceConnection: CustomTabsServiceConnection? = null
    private var session: CustomTabsSession? = null
    val authCodeDeferred = CompletableDeferred<AuthCodeResult>()

    /**
     * Open the auth URL in a Chrome Custom Tab with URL-change monitoring.
     * Returns the CustomTabsIntent to be launched by the Activity.
     */
    fun buildCustomTabIntent(
        authUrl: Uri,
        packageName: String?
    ): CustomTabsIntent {
        val intent = CustomTabsIntent.Builder(session)
            .setShowTitle(true)
            .build()

        // If we have a Chrome package, use it specifically
        if (packageName != null) {
            intent.intent.setPackage(packageName)
        }

        return intent
    }

    /**
     * Bind to Chrome Custom Tabs service and set up URL monitoring callback.
     */
    fun bindService(
        context: android.content.Context,
        chromePackage: String?
    ): Boolean {
        val pkg = chromePackage ?: return false

        val callback = object : CustomTabsCallback() {
            override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                if (navigationEvent == NAVIGATION_STARTED) {
                    val url = extras?.getString(EXTRA_URL) ?: return
                    AppLog.step("auth", "CustomTabsCallback: navigation to url (checking redirect)")

                    try {
                        val uri = Uri.parse(url)
                        if (uri.host == REDIRECT_HOST && uri.path?.startsWith(REDIRECT_PATH) == true) {
                            val code = uri.getQueryParameter("code")
                            val state = uri.getQueryParameter("state")
                            val error = uri.getQueryParameter("error")
                            val errorDescription = uri.getQueryParameter("error_description")

                            AppLog.step("auth", "CustomTabsCallback: redirect detected, code=${if (code != null) "***" else "null"} error=$error")

                            if (error != null) {
                                authCodeDeferred.complete(
                                    AuthCodeResult.Error(error, errorDescription ?: "Unknown error")
                                )
                            } else if (code != null && state != null) {
                                authCodeDeferred.complete(
                                    AuthCodeResult.Success(code, state)
                                )
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.e("AUTH", "CustomTabsCallback: error parsing URL: ${e.message}", e)
                    }
                }
            }
        }

        serviceConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0)
                session = client.newSession(callback)
                AppLog.step("auth", "CustomTabsService: connected, session created with callback")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                session = null
                AppLog.step("auth", "CustomTabsService: disconnected")
            }
        }

        return CustomTabsClient.bindCustomTabsService(context, pkg, serviceConnection!!)
    }

    /**
     * Unbind from Chrome Custom Tabs service and clean up.
     */
    fun unbind(context: android.content.Context) {
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                AppLog.w("AUTH", "CustomTabAuthCapture.unbind: ${e.message}")
            }
        }
        serviceConnection = null
        session = null
    }

    /**
     * Handle a potential auth redirect from onNewIntent (fallback if CustomTabsCallback misses it).
     * This can happen if the browser redirects to the URL and the Activity receives it via intent.
     */
    fun handleRedirectUri(uri: Uri) {
        if (uri.host == REDIRECT_HOST && uri.path?.startsWith(REDIRECT_PATH) == true) {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")

            if (error != null) {
                authCodeDeferred.complete(AuthCodeResult.Error(error, errorDescription ?: "Unknown error"))
            } else if (code != null && state != null) {
                authCodeDeferred.complete(AuthCodeResult.Success(code, state))
            }
        }
    }
}

/**
 * Option 3: Localhost HTTP server for the second OAuth2 step.
 *
 * Starts an ephemeral ServerSocket on a random port (localhost:0),
 * constructs the redirect_uri as http://localhost:<port>,
 * and captures the auth code from the redirect request.
 */
class LocalhostAuthServer {

    private var serverSocket: ServerSocket? = null
    var port: Int = 0
        private set

    /**
     * Start the localhost server. Returns the port number to use in the redirect_uri.
     */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val server = ServerSocket(0)  // OS assigns a random available port
        serverSocket = server
        port = server.localPort
        AppLog.step("auth", "LocalhostAuthServer: started on port $port")
        port
    }

    /**
     * Wait for the OAuth2 redirect to arrive. Blocks until a connection comes in.
     * Returns the auth code from the redirect URL query parameter.
     * Times out after the specified duration.
     */
    suspend fun awaitRedirect(timeoutMs: Long = 120_000L): AuthCodeResult = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IllegalStateException("Server not started")

        try {
            withTimeout(timeoutMs) {
                val socket: Socket = server.accept()
                handleConnection(socket)
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.w("AUTH", "LocalhostAuthServer: timeout waiting for redirect")
            AuthCodeResult.Cancelled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("AUTH", "LocalhostAuthServer: error: ${e.message}", e)
            AuthCodeResult.Error("server_error", e.message ?: "Unknown error")
        }
    }

    /**
     * Stop the server and release the port.
     */
    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            AppLog.w("AUTH", "LocalhostAuthServer.stop: ${e.message}")
        }
        serverSocket = null
        AppLog.step("auth", "LocalhostAuthServer: stopped")
    }

    private fun handleConnection(socket: Socket): AuthCodeResult {
        return socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val requestLine = reader.readLine() ?: return AuthCodeResult.Error("empty_request", "No request received")

            // Parse: GET /?code=XXX&state=YYY HTTP/1.1
            val parts = requestLine.split(" ")
            if (parts.size < 2) return AuthCodeResult.Error("malformed_request", "Bad request: $requestLine")

            val uri = Uri.parse("http://localhost${parts[1]}")
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")

            // Send a simple HTML response to the browser
            val writer = PrintWriter(it.getOutputStream(), true)
            val responseBody = if (error != null) {
                "<html><body><h2>Login failed</h2><p>$error: $errorDescription</p><p>You can close this tab.</p></body></html>"
            } else {
                "<html><body><h2>Login successful!</h2><p>You can close this tab and return to the app.</p></body></html>"
            }
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html")
            writer.println("Content-Length: ${responseBody.length}")
            writer.println("Connection: close")
            writer.println()
            writer.print(responseBody)
            writer.flush()

            if (error != null) {
                AuthCodeResult.Error(error, errorDescription ?: "Unknown error")
            } else if (code != null && state != null) {
                AppLog.step("auth", "LocalhostAuthServer: captured auth code ***")
                AuthCodeResult.Success(code, state)
            } else {
                AuthCodeResult.Error("missing_code", "No code parameter in redirect")
            }
        }
    }
}

/**
 * Result of capturing an auth code from a redirect.
 */
sealed class AuthCodeResult {
    data class Success(val code: String, val state: String) : AuthCodeResult()
    data class Error(val error: String, val description: String) : AuthCodeResult()
    object Cancelled : AuthCodeResult()
}

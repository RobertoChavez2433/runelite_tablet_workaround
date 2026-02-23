package com.runelitetablet.auth

import android.net.Uri
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Localhost HTTP server for OAuth2 redirect capture.
 *
 * Starts an ephemeral ServerSocket on a random port (localhost:0),
 * constructs the redirect_uri as http://localhost:<port>,
 * and captures auth data from the redirect request.
 *
 * Two capture modes:
 * - [awaitRedirect]: Single GET request with code/state in query params (Step 1 fallback)
 * - [awaitConsentRedirect]: Two-request flow for Step 2 consent:
 *   1. GET / -> serves forwarder HTML that extracts fragment params via JS
 *   2. POST /jws -> receives id_token + code + state from forwarder JS
 */
class LocalhostAuthServer {

    companion object {
        private val secureRandom = SecureRandom()
        private const val CSRF_TOKEN_LENGTH = 32
        private const val CSRF_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    private var serverSocket: ServerSocket? = null
    var port: Int = 0
        private set

    /**
     * Start the localhost server. Returns the port number to use in the redirect_uri.
     */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        // Bind to loopback only — prevents other apps on the network from connecting
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        port = server.localPort
        AppLog.step("auth", "LocalhostAuthServer: started on port $port")
        port
    }

    /**
     * Wait for a single OAuth2 redirect to arrive (GET with code/state in query params).
     * Used for simple code flow redirects.
     * Times out after the specified duration.
     */
    suspend fun awaitRedirect(timeoutMs: Long = 120_000L): AuthCodeResult = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IllegalStateException("Server not started")

        try {
            withTimeout(timeoutMs) {
                val socket: Socket = server.accept()
                handleCodeRedirect(socket)
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
     * Wait for the Step 2 consent redirect via the 2-request forwarder pattern.
     *
     * Request 1: GET / -> serves forwarder HTML page
     * Request 2: POST /jws -> receives id_token, code, state from forwarder JS
     *
     * The consent redirect lands on http://localhost:<port>#id_token=XXX&code=YYY&state=ZZZ.
     * The fragment (#...) is NOT sent to the server, so the forwarder HTML uses JavaScript
     * to extract fragment params and POST them back.
     *
     * @param expectedState The state nonce to validate against
     * @param timeoutMs Maximum time to wait for the full 2-request flow
     * @return ConsentResult with id_token on success, or error/cancellation
     */
    suspend fun awaitConsentRedirect(
        expectedState: String,
        timeoutMs: Long = 120_000L
    ): ConsentResult = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IllegalStateException("Server not started")

        try {
            withTimeout(timeoutMs) {
                // Generate a per-request CSRF token for the forwarder HTML
                val csrfToken = generateCsrfToken()

                // Request 1: Serve the forwarder HTML (with embedded CSRF token)
                val firstSocket: Socket = server.accept()
                serveForwarderHtml(firstSocket, csrfToken)
                AppLog.step("auth", "awaitConsentRedirect: forwarder HTML served, waiting for POST")

                // Request 2: Receive the fragment params via POST
                val secondSocket: Socket = server.accept()
                handleConsentPost(secondSocket, expectedState, csrfToken)
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.w("AUTH", "awaitConsentRedirect: timeout waiting for consent redirect")
            ConsentResult.Cancelled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("AUTH", "awaitConsentRedirect: error: ${e.message}", e)
            ConsentResult.Error("server_error", e.message ?: "Unknown error")
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

    // -------------------------------------------------------------------------
    // Single-request code redirect handler (existing flow)
    // -------------------------------------------------------------------------

    private fun handleCodeRedirect(socket: Socket): AuthCodeResult {
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

            // Send styled HTML response with auto-return to the app
            val writer = PrintWriter(it.getOutputStream(), true)
            val responseBody = if (error != null) {
                buildErrorHtml(error, errorDescription)
            } else {
                successHtml
            }
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html; charset=utf-8")
            writer.println("Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}")
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

    // -------------------------------------------------------------------------
    // Two-request consent redirect handler (Step 2)
    // -------------------------------------------------------------------------

    /**
     * Serve the forwarder HTML page that extracts fragment params via JavaScript.
     * The page POSTs the extracted params back to /jws on this same server.
     */
    private fun serveForwarderHtml(socket: Socket, csrfToken: String) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            // Read the request line (we don't need to parse it — just serve the HTML)
            reader.readLine()
            // Consume remaining headers
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                line = reader.readLine()
            }

            val html = buildForwarderHtml(csrfToken)
            val writer = PrintWriter(it.getOutputStream(), true)
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html; charset=utf-8")
            writer.println("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}")
            writer.println("Connection: close")
            writer.println()
            writer.print(html)
            writer.flush()
        }
    }

    /**
     * Handle the POST /jws request from the forwarder page.
     * The body is application/x-www-form-urlencoded with id_token, code, state.
     */
    private fun handleConsentPost(socket: Socket, expectedState: String, expectedCsrf: String): ConsentResult {
        return socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val requestLine = reader.readLine()
                ?: return ConsentResult.Error("empty_request", "No request received")

            // Parse request line: POST /jws HTTP/1.1
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[1].startsWith("/jws")) {
                // Not the expected POST — send error response
                sendJsonResponse(it, 400, """{"error":"unexpected_request"}""")
                return ConsentResult.Error("unexpected_request", "Expected POST /jws, got: $requestLine")
            }

            // Read headers to find Content-Length
            var contentLength = 0
            var headerLine = reader.readLine()
            while (headerLine != null && headerLine.isNotEmpty()) {
                if (headerLine.lowercase().startsWith("content-length:")) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                headerLine = reader.readLine()
            }

            // Read the body
            if (contentLength <= 0 || contentLength > 65536) {
                sendJsonResponse(it, 400, """{"error":"invalid_request"}""")
                return ConsentResult.Error("invalid_request", "Invalid content length")
            }
            val bodyChars = CharArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = reader.read(bodyChars, totalRead, contentLength - totalRead)
                if (read == -1) break
                totalRead += read
            }
            val body = String(bodyChars)

            // Parse URL-encoded body: id_token=XXX&code=YYY&state=ZZZ&_csrf=TOKEN
            val params = parseUrlEncodedBody(body)
            val csrfToken = params["_csrf"]
            if (csrfToken == null || csrfToken != expectedCsrf) {
                sendJsonResponse(it, 403, """{"error":"csrf_mismatch"}""")
                AppLog.e("AUTH", "handleConsentPost: CSRF token mismatch — rejecting POST")
                return ConsentResult.Error("csrf_mismatch", "Security check failed (CSRF)")
            }

            val idToken = params["id_token"]
            val state = params["state"]
            val error = params["error"]
            val errorDescription = params["error_description"]

            if (error != null) {
                sendJsonResponse(it, 200, """{"status":"error"}""")
                return ConsentResult.Error(error, errorDescription ?: "Consent error")
            }

            if (idToken == null) {
                sendJsonResponse(it, 200, """{"status":"error"}""")
                return ConsentResult.Error("missing_id_token", "No id_token in consent response")
            }

            if (state == null || state != expectedState) {
                sendJsonResponse(it, 200, """{"status":"error"}""")
                AppLog.e("AUTH", "handleConsentPost: state mismatch — potential CSRF attack")
                return ConsentResult.Error("state_mismatch", "Security check failed (state mismatch)")
            }

            // Send success response (the forwarder JS will show "Login successful!")
            sendJsonResponse(it, 200, """{"status":"ok"}""")

            AppLog.step("auth", "handleConsentPost: captured consent id_token ***")
            ConsentResult.Success(idToken = idToken, state = state ?: expectedState)
        }
    }

    /**
     * Parse a URL-encoded body string into key-value pairs.
     */
    private fun parseUrlEncodedBody(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = URLDecoder.decode(parts[1], "UTF-8")
                key to value
            } else {
                null
            }
        }.toMap()
    }

    /**
     * Send a simple JSON response on a socket.
     */
    private fun sendJsonResponse(socket: Socket, statusCode: Int, jsonBody: String) {
        try {
            val statusText = if (statusCode == 200) "OK" else "Bad Request"
            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println("HTTP/1.1 $statusCode $statusText")
            writer.println("Content-Type: application/json; charset=utf-8")
            writer.println("Content-Length: ${jsonBody.toByteArray(Charsets.UTF_8).size}")
            writer.println("Connection: close")
            writer.println()
            writer.print(jsonBody)
            writer.flush()
        } catch (e: Exception) {
            AppLog.w("AUTH", "sendJsonResponse: failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // HTML builders
    // -------------------------------------------------------------------------

    /**
     * Forwarder HTML page for Step 2 consent.
     * When the consent redirect arrives at http://localhost:<port>#id_token=XXX&code=YYY,
     * the browser doesn't send the fragment to the server. This page extracts the
     * fragment params via JavaScript and POSTs them back to /jws on this same server.
     */
    private fun generateCsrfToken(): String = buildString(CSRF_TOKEN_LENGTH) {
        repeat(CSRF_TOKEN_LENGTH) { append(CSRF_CHARS[secureRandom.nextInt(CSRF_CHARS.length)]) }
    }

    private fun buildForwarderHtml(csrfToken: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Completing Login</title>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    text-align: center;
                    padding: 40px 20px;
                    background: #1a1a2e;
                    color: #e0e0e0;
                    margin: 0;
                }
                .container { max-width: 400px; margin: 0 auto; }
                h2 { color: #4ecca3; margin-bottom: 8px; }
                p { color: #b0b0b0; line-height: 1.5; }
            </style>
        </head>
        <body>
            <div class="container">
                <h2>Completing login...</h2>
                <p>Please wait while we finish setting up your session.</p>
            </div>
            <script>
                function onLoad() {
                    if (window.location.hash.length > 1) {
                        var params = new URLSearchParams(window.location.hash.slice(1));
                        var body = new URLSearchParams();
                        params.forEach(function(v, k) { body.append(k, v); });
                        body.append('_csrf', '$csrfToken');
                        fetch('/jws', {
                            method: 'POST',
                            body: body,
                            headers: {'Content-Type': 'application/x-www-form-urlencoded'}
                        }).then(function() {
                            document.querySelector('.container').innerHTML =
                                '<h2>Login successful!</h2>' +
                                '<p>Returning to RuneLite for Tablet...</p>';
                            setTimeout(function() {
                                window.location = 'intent:#Intent;package=com.runelitetablet;end';
                            }, 500);
                        }).catch(function() {
                            document.querySelector('.container').innerHTML =
                                '<h2>Login error</h2>' +
                                '<p>Could not complete login. Please return to the app and try again.</p>' +
                                '<a href="intent:#Intent;package=com.runelitetablet;end" ' +
                                'style="display:inline-block;margin-top:20px;padding:12px 24px;' +
                                'background:#4ecca3;color:#1a1a2e;text-decoration:none;' +
                                'border-radius:8px;font-weight:bold;">Return to app</a>';
                        });
                    } else {
                        document.querySelector('.container').innerHTML =
                            '<h2>Login failed</h2>' +
                            '<p>No authentication data received.</p>' +
                            '<a href="intent:#Intent;package=com.runelitetablet;end" ' +
                            'style="display:inline-block;margin-top:20px;padding:12px 24px;' +
                            'background:#4ecca3;color:#1a1a2e;text-decoration:none;' +
                            'border-radius:8px;font-weight:bold;">Return to app</a>';
                    }
                }
                window.onload = onLoad;
            </script>
        </body>
        </html>
    """.trimIndent()

    /**
     * Build a styled success HTML page with auto-return to the app via intent URI.
     * The intent scheme triggers the app to come back to the foreground.
     */
    private val successHtml: String by lazy { """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Login Successful</title>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    text-align: center;
                    padding: 40px 20px;
                    background: #1a1a2e;
                    color: #e0e0e0;
                    margin: 0;
                }
                .container { max-width: 400px; margin: 0 auto; }
                .icon { font-size: 64px; margin-bottom: 16px; }
                h2 { color: #4ecca3; margin-bottom: 8px; }
                p { color: #b0b0b0; line-height: 1.5; }
                a {
                    display: inline-block;
                    margin-top: 20px;
                    padding: 12px 24px;
                    background: #4ecca3;
                    color: #1a1a2e;
                    text-decoration: none;
                    border-radius: 8px;
                    font-weight: bold;
                }
                a:active { background: #3ba882; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="icon">&#x2705;</div>
                <h2>Login successful!</h2>
                <p>Returning to RuneLite for Tablet...</p>
                <a href="intent:#Intent;package=com.runelitetablet;end">
                    Tap here if not redirected automatically
                </a>
            </div>
            <script>
                // Try to return to app automatically via intent scheme
                setTimeout(function() {
                    window.location = "intent:#Intent;package=com.runelitetablet;end";
                }, 500);
            </script>
        </body>
        </html>
    """.trimIndent() }

    /**
     * Build a styled error HTML page.
     * HTML-escapes error strings to prevent XSS from malicious query parameters.
     */
    private fun buildErrorHtml(error: String, description: String?): String {
        val safeError = htmlEscape(error)
        val safeDesc = htmlEscape(description ?: "An error occurred during login.")
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Login Failed</title>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    text-align: center;
                    padding: 40px 20px;
                    background: #1a1a2e;
                    color: #e0e0e0;
                    margin: 0;
                }
                .container { max-width: 400px; margin: 0 auto; }
                .icon { font-size: 64px; margin-bottom: 16px; }
                h2 { color: #e74c3c; margin-bottom: 8px; }
                p { color: #b0b0b0; line-height: 1.5; }
                .error-detail {
                    margin-top: 12px;
                    padding: 12px;
                    background: rgba(231, 76, 60, 0.1);
                    border-radius: 8px;
                    font-size: 14px;
                    color: #e0a0a0;
                }
                a {
                    display: inline-block;
                    margin-top: 20px;
                    padding: 12px 24px;
                    background: #4ecca3;
                    color: #1a1a2e;
                    text-decoration: none;
                    border-radius: 8px;
                    font-weight: bold;
                }
                a:active { background: #3ba882; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="icon">&#x274C;</div>
                <h2>Login failed</h2>
                <p>$safeDesc</p>
                <div class="error-detail">Error: $safeError</div>
                <a href="intent:#Intent;package=com.runelitetablet;end">
                    Return to app
                </a>
            </div>
        </body>
        </html>
    """.trimIndent()
    }

    /** Minimal HTML escaping to prevent XSS from OAuth error parameters. */
    private fun htmlEscape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}

/**
 * Result of capturing an auth code from a redirect.
 */
sealed class AuthCodeResult {
    data class Success(val code: String, val state: String) : AuthCodeResult() {
        override fun toString(): String = "Success(code=***, state=***)"
    }
    data class Error(val error: String, val description: String) : AuthCodeResult()
    object Cancelled : AuthCodeResult()
}

/**
 * Result of the Step 2 consent redirect capture.
 * Contains the id_token needed for game session API calls.
 */
sealed class ConsentResult {
    data class Success(val idToken: String, val state: String) : ConsentResult() {
        /** Redact sensitive token data from logs. */
        override fun toString(): String = "ConsentSuccess(idToken=***, state=***)"
    }
    data class Error(val error: String, val description: String) : ConsentResult()
    object Cancelled : ConsentResult()
}

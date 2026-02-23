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

/**
 * Localhost HTTP server for OAuth2 redirect capture.
 *
 * Starts an ephemeral ServerSocket on a random port (localhost:0),
 * constructs the redirect_uri as http://localhost:<port>,
 * and captures the auth code from the redirect request.
 *
 * Used for both the first and second OAuth2 authentication steps.
 */
class LocalhostAuthServer {

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

            // Send styled HTML response with auto-return to the app
            val writer = PrintWriter(it.getOutputStream(), true)
            val responseBody = if (error != null) {
                buildErrorHtml(error, errorDescription)
            } else {
                buildSuccessHtml()
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

    /**
     * Build a styled success HTML page with auto-return to the app via intent URI.
     * The intent scheme triggers the app to come back to the foreground.
     */
    private fun buildSuccessHtml(): String = """
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
    """.trimIndent()

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

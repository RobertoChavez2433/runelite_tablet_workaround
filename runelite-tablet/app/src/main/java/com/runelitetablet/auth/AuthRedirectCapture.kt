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
    data class Success(val code: String, val state: String) : AuthCodeResult() {
        override fun toString(): String = "Success(code=***, state=***)"
    }
    data class Error(val error: String, val description: String) : AuthCodeResult()
    object Cancelled : AuthCodeResult()
}

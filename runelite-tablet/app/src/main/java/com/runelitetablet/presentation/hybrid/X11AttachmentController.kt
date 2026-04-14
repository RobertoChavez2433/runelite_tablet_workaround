package com.runelitetablet.presentation.hybrid

import com.runelitetablet.domain.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the X11 binder connection state machine: attach, retry, reconnect.
 * Extracted from HybridX11HostActivity for testability.
 *
 * State: not attached -> logcat attached -> x connection attached (done)
 * On binder death or bridge reconnect: reset and retry.
 */
class X11AttachmentController(
    private val connector: X11ServiceConnector,
    private val onStatusUpdate: (String) -> Unit,
    private val onStatusVisible: (Boolean) -> Unit,
    private val onAttached: () -> Unit,
    private val logger: Logger? = null
) {
    var logcatAttached = false
        private set
    var xConnectionAttached = false
        private set
    var pendingBridgeReconnect = false
    private var lastConnectionRequestAtMs = 0L
    private var attachJob: Job? = null

    companion object {
        const val ATTACH_INTERVAL_MS = 250L
        const val CONNECTION_THROTTLE_MS = 1_000L
        const val MAX_RETRIES = Int.MAX_VALUE // loops until cancelled
    }

    fun startAttachLoop(scope: CoroutineScope) {
        attachJob?.cancel()
        attachJob = scope.launch {
            while (isActive) {
                tryAttach()
                delay(ATTACH_INTERVAL_MS)
            }
        }
    }

    fun cancelAttachLoop() {
        attachJob?.cancel()
    }

    fun notifyBridgeStateChanged() {
        pendingBridgeReconnect = true
        xConnectionAttached = false
        logcatAttached = false
    }

    fun notifyConnectionStateChanged(connected: Boolean) {
        if (connected) {
            xConnectionAttached = true
            onStatusUpdate("connected=true")
            onStatusVisible(false)
            attachJob?.cancel()
        } else {
            xConnectionAttached = false
            onStatusVisible(true)
            onStatusUpdate("connected=false")
        }
    }

    fun tryAttach() {
        val forceReconnect = pendingBridgeReconnect
        if (!forceReconnect && connector.isConnected()) {
            if (!xConnectionAttached) {
                xConnectionAttached = true
                onStatusUpdate("connected=true")
                onStatusVisible(false)
                attachJob?.cancel()
            }
            return
        }

        xConnectionAttached = false
        onStatusVisible(true)

        if (!connector.isServiceAvailable()) {
            maybeRequestConnection()
            onStatusUpdate("waiting for binder")
            return
        }
        if (!connector.isBinderAlive()) {
            maybeRequestConnection()
            onStatusUpdate("binder dead, waiting for replacement")
            return
        }

        if (!logcatAttached) {
            try {
                connector.attachLogcat()
                logcatAttached = true
            } catch (e: Exception) {
                logger?.w("HYBRID_X11", "attachLogcat failed: ${e.message}")
                logcatAttached = true // don't block on logcat
            }
        }

        try {
            val attached = connector.attachXConnection()
            if (!attached) {
                maybeRequestConnection()
                onStatusUpdate("binder alive, waiting for xConnection fd")
                return
            }

            xConnectionAttached = true
            pendingBridgeReconnect = false
            onStatusUpdate("attached xConnection")
            onStatusVisible(false)
            attachJob?.cancel()
            onAttached()
        } catch (e: Exception) {
            logger?.e("HYBRID_X11", "attach failed: ${e.message}", e)
            onStatusUpdate("attach failed: ${e.message}")
        }
    }

    private fun maybeRequestConnection() {
        val now = System.currentTimeMillis()
        if (now - lastConnectionRequestAtMs < CONNECTION_THROTTLE_MS) return
        lastConnectionRequestAtMs = now
        try {
            connector.requestConnection()
        } catch (e: Exception) {
            logger?.w("HYBRID_X11", "requestConnection failed: ${e.message}")
        }
    }
}

/**
 * Interface wrapping the native LorieView + binder calls.
 * Production: delegates to real LorieView static methods.
 * Tests: fake implementation for state machine testing.
 */
interface X11ServiceConnector {
    fun isConnected(): Boolean
    fun isServiceAvailable(): Boolean
    fun isBinderAlive(): Boolean
    fun attachLogcat()
    fun attachXConnection(): Boolean
    fun requestConnection()
}

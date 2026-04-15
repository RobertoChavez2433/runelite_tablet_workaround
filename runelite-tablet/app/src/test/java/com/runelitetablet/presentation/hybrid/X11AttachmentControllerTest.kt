package com.runelitetablet.presentation.hybrid

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class X11AttachmentControllerTest {

    private lateinit var connector: FakeX11ServiceConnector
    private lateinit var controller: X11AttachmentController
    private val statusMessages = mutableListOf<String>()
    private var statusVisible = false
    private var attachedCalled = false

    @Before
    fun setup() {
        connector = FakeX11ServiceConnector()
        statusMessages.clear()
        statusVisible = false
        attachedCalled = false
        controller = X11AttachmentController(
            connector = connector,
            onStatusUpdate = { statusMessages.add(it) },
            onStatusVisible = { statusVisible = it },
            onAttached = { attachedCalled = true }
        )
    }

    @Test
    fun `successful attachment on first try`() {
        connector.connected = false
        connector.serviceAvailable = true
        connector.binderAlive = true
        connector.xConnectionAvailable = true

        controller.tryAttach()

        assertTrue(controller.xConnectionAttached)
        assertTrue(controller.logcatAttached)
        assertTrue(attachedCalled)
        assertFalse(statusVisible)
    }

    @Test
    fun `already connected marks attached and hides status`() {
        connector.connected = true
        controller.tryAttach()

        assertTrue(controller.xConnectionAttached)
        assertFalse(statusVisible)
    }

    @Test
    fun `service unavailable shows waiting status`() {
        connector.connected = false
        connector.serviceAvailable = false

        controller.tryAttach()

        assertFalse(controller.xConnectionAttached)
        assertTrue(statusVisible)
        assertTrue(statusMessages.any { it.contains("waiting for binder") })
    }

    @Test
    fun `dead binder shows waiting status`() {
        connector.connected = false
        connector.serviceAvailable = true
        connector.binderAlive = false

        controller.tryAttach()

        assertFalse(controller.xConnectionAttached)
        assertTrue(statusMessages.any { it.contains("binder dead") })
    }

    @Test
    fun `x connection unavailable waits`() {
        connector.connected = false
        connector.serviceAvailable = true
        connector.binderAlive = true
        connector.xConnectionAvailable = false

        controller.tryAttach()

        assertFalse(controller.xConnectionAttached)
        assertTrue(controller.logcatAttached)
        assertTrue(statusMessages.any { it.contains("waiting for xConnection") })
    }

    @Test
    fun `bridge state change resets attachment`() {
        connector.connected = true
        controller.tryAttach()
        assertTrue(controller.xConnectionAttached)

        controller.notifyBridgeStateChanged()

        assertFalse(controller.xConnectionAttached)
        assertFalse(controller.logcatAttached)
        assertTrue(controller.pendingBridgeReconnect)
    }

    @Test
    fun `connection state change to connected`() {
        controller.notifyConnectionStateChanged(true)

        assertTrue(controller.xConnectionAttached)
        assertFalse(statusVisible)
    }

    @Test
    fun `connection state change to disconnected`() {
        controller.notifyConnectionStateChanged(false)

        assertFalse(controller.xConnectionAttached)
        assertTrue(statusVisible)
    }

    @Test
    fun `logcat failure does not block x connection`() {
        connector.connected = false
        connector.serviceAvailable = true
        connector.binderAlive = true
        connector.logcatThrows = true
        connector.xConnectionAvailable = true

        controller.tryAttach()

        assertTrue(controller.logcatAttached) // marked as done despite error
        assertTrue(controller.xConnectionAttached)
    }

    @Test
    fun `x connection failure shows error status`() {
        connector.connected = false
        connector.serviceAvailable = true
        connector.binderAlive = true
        connector.xConnectionThrows = true

        controller.tryAttach()

        assertFalse(controller.xConnectionAttached)
        assertTrue(statusMessages.any { it.contains("attach failed") })
    }

    @Test
    fun `force reconnect retries even if connected`() {
        connector.connected = true
        connector.serviceAvailable = true
        connector.binderAlive = true
        connector.xConnectionAvailable = true
        controller.pendingBridgeReconnect = true

        controller.tryAttach()

        assertTrue(controller.xConnectionAttached)
        assertFalse(controller.pendingBridgeReconnect)
    }

    @Test
    fun `connection requests are throttled`() {
        connector.connected = false
        connector.serviceAvailable = false

        controller.tryAttach() // first request
        controller.tryAttach() // should be throttled

        assertEquals(1, connector.connectionRequestCount)
    }

    @Test
    fun `start and cancel attach loop`() = runTest {
        connector.connected = true
        controller.startAttachLoop(this)
        advanceTimeBy(100)
        controller.cancelAttachLoop()
        assertTrue(controller.xConnectionAttached)
    }

    @Test
    fun `startAttachLoop skips when already connected`() = runTest {
        connector.connected = true
        controller.tryAttach() // sets xConnectionAttached = true
        assertTrue(controller.xConnectionAttached)

        connector.connectionRequestCount = 0
        controller.startAttachLoop(this)
        advanceTimeBy(1000)

        // Loop should not have started — no connection requests
        assertEquals(0, connector.connectionRequestCount)
    }

    @Test
    fun `startAttachLoop runs when connected but bridge reconnect pending`() = runTest {
        connector.connected = true
        connector.serviceAvailable = true
        connector.binderAlive = true
        connector.xConnectionAvailable = true
        controller.tryAttach()
        assertTrue(controller.xConnectionAttached)

        controller.notifyBridgeStateChanged()
        assertTrue(controller.pendingBridgeReconnect)
        assertFalse(controller.xConnectionAttached)

        controller.startAttachLoop(this)
        advanceTimeBy(300)

        // Loop should have run and reconnected
        assertTrue(controller.xConnectionAttached)
        assertFalse(controller.pendingBridgeReconnect)
    }
}

class FakeX11ServiceConnector : X11ServiceConnector {
    var connected = false
    var serviceAvailable = true
    var binderAlive = true
    var xConnectionAvailable = true
    var logcatThrows = false
    var xConnectionThrows = false
    var connectionRequestCount = 0

    override fun isConnected() = connected
    override fun isServiceAvailable() = serviceAvailable
    override fun isBinderAlive() = binderAlive

    override fun attachLogcat() {
        if (logcatThrows) throw RuntimeException("logcat unavailable")
    }

    override fun attachXConnection(): Boolean {
        if (xConnectionThrows) throw RuntimeException("attach failed")
        return xConnectionAvailable
    }

    override fun requestConnection() {
        connectionRequestCount++
    }
}

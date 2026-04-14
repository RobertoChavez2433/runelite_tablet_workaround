package com.runelitetablet.presentation.hybrid

import android.content.SharedPreferences
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.FdTracker
import com.termux.x11.LorieView

/**
 * Production X11ServiceConnector backed by real LorieView and HybridX11Bridge.
 */
class LorieServiceConnector(
    private val lorieView: LorieView,
    private val sharedPrefs: SharedPreferences
) : X11ServiceConnector {

    override fun isConnected(): Boolean = LorieView.connected()

    override fun isServiceAvailable(): Boolean = HybridX11Bridge.currentService() != null

    override fun isBinderAlive(): Boolean =
        HybridX11Bridge.currentService()?.asBinder()?.isBinderAlive == true

    override fun attachLogcat() {
        val service = HybridX11Bridge.currentService() ?: run {
            AppLog.d("HYBRID_X11", "LorieServiceConnector.attachLogcat: no service"); return
        }
        val startNs = System.nanoTime()
        val logcat = service.getLogcatOutput()
        if (logcat != null) {
            val fd = logcat.detachFd()
            FdTracker.detached(fd, "logcat", "LorieServiceConnector.attachLogcat")
            AppLog.fd("detach", fd, "logcat", "LorieServiceConnector.attachLogcat")
            LorieView.startLogcat(fd)
            FdTracker.closed(fd, "logcat", "consumed by JNI startLogcat")
            AppLog.ipc("send", "jni_startLogcat", (System.nanoTime() - startNs) / 1_000_000, "fd=$fd")
        } else {
            AppLog.w("HYBRID_X11", "LorieServiceConnector.attachLogcat: getLogcatOutput returned null")
        }
    }

    override fun attachXConnection(): Boolean {
        val service = HybridX11Bridge.currentService() ?: run {
            AppLog.d("HYBRID_X11", "LorieServiceConnector.attachXConnection: no service"); return false
        }
        val binderState = service.asBinder()?.let { if (it.isBinderAlive) "alive" else "dead" } ?: "null"
        AppLog.d("HYBRID_X11", "LorieServiceConnector.attachXConnection: binder=$binderState")

        val startNs = System.nanoTime()
        val fd = service.getXConnection() ?: return false
        val detached = fd.detachFd()
        if (detached < 0) {
            AppLog.e("HYBRID_X11", "LorieServiceConnector.attachXConnection: detachFd returned $detached (invalid)")
            return false
        }
        FdTracker.detached(detached, "xConnection", "LorieServiceConnector.attachXConnection")
        AppLog.fd("detach", detached, "xConnection", "LorieServiceConnector.attachXConnection")
        LorieView.connect(detached)
        FdTracker.closed(detached, "xConnection", "consumed by JNI connect")
        val connectLatencyMs = (System.nanoTime() - startNs) / 1_000_000
        lorieView.triggerCallback()
        com.termux.x11.MainActivity.getInstance().clientConnectedStateChanged()
        lorieView.reloadPreferences(sharedPrefs)
        AppLog.ipc("send", "jni_connect", connectLatencyMs, "fd=$detached binder=$binderState")
        return true
    }

    override fun requestConnection() {
        val requested = LorieView.requestConnection()
        AppLog.step("hybrid_x11", "LorieServiceConnector: requestConnection requested=$requested")
    }
}

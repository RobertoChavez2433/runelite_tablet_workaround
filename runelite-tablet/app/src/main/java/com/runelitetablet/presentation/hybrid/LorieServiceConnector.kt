package com.runelitetablet.presentation.hybrid

import android.content.SharedPreferences
import com.runelitetablet.logging.AppLog
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
        val service = HybridX11Bridge.currentService() ?: return
        val logcat = service.getLogcatOutput()
        if (logcat != null) {
            LorieView.startLogcat(logcat.detachFd())
            AppLog.step("hybrid_x11", "LorieServiceConnector: attached logcat fd")
        }
    }

    override fun attachXConnection(): Boolean {
        val service = HybridX11Bridge.currentService() ?: return false
        val fd = service.getXConnection() ?: return false
        val detached = fd.detachFd()
        LorieView.connect(detached)
        lorieView.triggerCallback()
        com.termux.x11.MainActivity.getInstance().clientConnectedStateChanged()
        lorieView.reloadPreferences(sharedPrefs)
        AppLog.step("hybrid_x11", "LorieServiceConnector: attached xConnection fd=$detached")
        return true
    }

    override fun requestConnection() {
        val requested = LorieView.requestConnection()
        AppLog.step("hybrid_x11", "LorieServiceConnector: requestConnection requested=$requested")
    }
}

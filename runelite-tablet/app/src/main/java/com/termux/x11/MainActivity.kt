package com.termux.x11

import android.os.Handler
import android.os.Looper
import com.runelitetablet.logging.AppLog

/**
 * Minimal shim that satisfies the JNI contract expected by Xlorie.
 *
 * This is not an Android Activity. It is a singleton bridge the native code can
 * call back into when the X connection state changes.
 */
class MainActivity private constructor() {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var connectionChangedListener: ((Boolean) -> Unit)? = null

    fun setConnectionChangedListener(listener: ((Boolean) -> Unit)?) {
        connectionChangedListener = listener
    }

    fun clientConnectedStateChanged() {
        val connected = LorieView.connected()
        AppLog.step("hybrid_x11", "Xlorie MainActivity shim: clientConnectedStateChanged connected=$connected")
        connectionChangedListener?.let { listener ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener(connected)
            } else {
                mainHandler.post { listener(connected) }
            }
        }
    }

    companion object {
        private val instance = MainActivity()

        @JvmStatic
        fun getInstance(): MainActivity = instance
    }
}

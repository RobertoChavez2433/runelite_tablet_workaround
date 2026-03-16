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
    private var connectionChangedListener: (() -> Unit)? = null

    fun setConnectionChangedListener(listener: (() -> Unit)?) {
        connectionChangedListener = listener
    }

    fun clientConnectedStateChanged() {
        AppLog.step("hybrid_x11", "Xlorie MainActivity shim: clientConnectedStateChanged")
        connectionChangedListener?.let { listener ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener()
            } else {
                mainHandler.post(listener)
            }
        }
    }

    companion object {
        private val instance = MainActivity()

        @JvmStatic
        fun getInstance(): MainActivity = instance
    }
}

package com.runelitetablet.presentation.hybrid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.runelitetablet.logging.AppLog
import com.termux.x11.ICmdEntryInterface

/**
 * Receives the stock termux-x11 ACTION_START binder handoff when the loader is
 * pointed at our package via TERMUX_X11_OVERRIDE_PACKAGE.
 */
class TermuxX11StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION_START) {
            AppLog.w("HYBRID_X11", "TermuxX11StartReceiver: ignoring action=$action")
            return
        }

        val bundle = intent.getBundleExtra(null as String?)
        val binder: IBinder? = bundle?.getBinder(null)
        if (binder == null) {
            AppLog.w("HYBRID_X11", "TermuxX11StartReceiver: ACTION_START received without binder")
            HybridX11Bridge.clear("missing_binder")
            return
        }
        val binderToken = binder.hashCode()
        if (!binder.isBinderAlive) {
            if (lastIgnoredDeadBinderToken != binderToken) {
                lastIgnoredDeadBinderToken = binderToken
                AppLog.w("HYBRID_X11", "TermuxX11StartReceiver: ignoring dead ACTION_START binder")
            }
            return
        }
        lastIgnoredDeadBinderToken = 0

        val currentBinder = HybridX11Bridge.currentService()?.asBinder()
        if (currentBinder != null && currentBinder == binder && currentBinder.isBinderAlive) {
            // Suppress log spam — only log the first duplicate and then every 30 seconds
            val now = System.currentTimeMillis()
            if (now - lastDuplicateLogTimeMs > DUPLICATE_LOG_INTERVAL_MS) {
                val suppressed = duplicatesSinceLastLog
                val suffix = if (suppressed > 0) " ($suppressed suppressed)" else ""
                AppLog.d("HYBRID_X11", "TermuxX11StartReceiver: duplicate binder, skipping$suffix")
                lastDuplicateLogTimeMs = now
                duplicatesSinceLastLog = 0
            } else {
                duplicatesSinceLastLog++
            }
            return
        }

        // Race check: binder may have died between line 30 check and here
        if (!binder.isBinderAlive) {
            AppLog.w("HYBRID_X11", "TermuxX11StartReceiver: binder died between checks (race)")
            return
        }

        val service = ICmdEntryInterface.Stub.asInterface(binder)
        if (service == null) {
            AppLog.w(
                "HYBRID_X11",
                "TermuxX11StartReceiver: ACTION_START binder could not be converted, binder=${HybridX11Bridge.describeBinder(binder)}"
            )
            HybridX11Bridge.clear("stub_conversion_failed")
            return
        }

        AppLog.step(
            "hybrid_x11",
            "TermuxX11StartReceiver: received ACTION_START binder=${HybridX11Bridge.describeBinder(binder)}"
        )
        HybridX11Bridge.attach(service)
    }

    companion object {
        const val ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START"
        private const val DUPLICATE_LOG_INTERVAL_MS = 30_000L

        @Volatile
        private var lastIgnoredDeadBinderToken: Int = 0
        @Volatile
        private var lastDuplicateLogTimeMs: Long = 0L
        @Volatile
        private var duplicatesSinceLastLog: Int = 0
    }
}

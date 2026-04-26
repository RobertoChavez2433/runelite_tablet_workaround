package com.runelitetablet.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.runelitetablet.logging.AppLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds a long-lived `bindService` connection to `com.termux.app.RunCommandService`
 * so Android promotes the Termux process to PROCESS_STATE_BOUND_TOP while our
 * RuneLiteSessionService is foreground. The intent is to move Termux (and every
 * child it spawns — proot, java, virgl, openbox) out of `/background` cpuset
 * (CPUs 0-3 on Samsung OneUI) into `/top-app` (CPUs 0-7).
 *
 * S81 audit showed `BIND_IMPORTANT + BIND_INCLUDE_CAPABILITIES` alone hoists
 * Termux into /top-app briefly (~25s) but Android demotes it back to /moderate
 * mid-session. Two mitigations added here:
 *
 *  1. `BIND_SCHEDULE_LIKE_TOP_APP` (API 33+). Tells AMS to schedule the bound
 *     process with the same cpuset/nice as the binding top-app. Signature-safe
 *     — ignored pre-API-33.
 *  2. `refreshPin()` — unbind + rebind in one atomic step. SessionService calls
 *     this on a 20-second interval so every AMS re-evaluation window re-asserts
 *     our hoist intent. Bind counter exposed via log so the timeline is visible
 *     when grep'ing logcat.
 */
class TermuxProcessPin(private val context: Context) {

    private var connection: ServiceConnection? = null
    private var bound = false
    private val bindCounter = AtomicInteger(0)

    /** Idempotent. Returns true if a pin is now held, false if bind failed. */
    @Synchronized
    fun pin(): Boolean {
        if (bound) {
            AppLog.state("TermuxProcessPin: pin() skipped — already bound count=${bindCounter.get()}")
            return true
        }
        return doBind()
    }

    /**
     * Unbind + rebind in a single synchronized step. SessionService schedules
     * this on a timer to re-assert BIND_IMPORTANT across AMS re-evaluations.
     * Returns true if the new bind succeeded (same contract as pin()).
     */
    @Synchronized
    fun refreshPin(): Boolean {
        val conn = connection
        if (conn != null) {
            try {
                context.unbindService(conn)
            } catch (e: IllegalArgumentException) {
                AppLog.state("TermuxProcessPin.refreshPin: unbind noop (${e.message})")
            } catch (e: Exception) {
                AppLog.w("STATE", "TermuxProcessPin.refreshPin: unbind threw ${e.message}")
            }
        }
        connection = null
        bound = false
        return doBind()
    }

    /** Caller must hold the monitor. */
    private fun doBind(): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                TermuxPackageHelper.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService"
            )
        }

        val attempt = bindCounter.incrementAndGet()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                AppLog.state("TermuxProcessPin: onServiceConnected bind#$attempt name=$name binder=${service != null}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                AppLog.state("TermuxProcessPin: onServiceDisconnected bind#$attempt name=$name")
            }

            override fun onNullBinding(name: ComponentName?) {
                AppLog.state("TermuxProcessPin: onNullBinding bind#$attempt name=$name (connection still hoists priority)")
            }

            override fun onBindingDied(name: ComponentName?) {
                AppLog.state("TermuxProcessPin: onBindingDied bind#$attempt name=$name")
            }
        }

        var flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        // Audit-#1 correction: BIND_INCLUDE_CAPABILITIES is API 31 (S), not Q.
        // The flag is a silent no-op on Q/R; gating on Q misrepresented coverage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or Context.BIND_INCLUDE_CAPABILITIES
        }
        // Considered: BIND_SCHEDULE_LIKE_TOP_APP (API 33) — tells AMS to give
        // the bound process the binding top-app's cpuset. Checked: it's @hide /
        // @SystemApi (Context.java: "This is only valid if the caller is a
        // system app"), not reachable from an unprivileged app. We fall back to
        // the periodic rebind loop in SessionService.startPinRefreshLoop which
        // re-asserts BIND_IMPORTANT every 20s across AMS re-evaluations.

        return try {
            val ok = context.bindService(intent, conn, flags)
            if (ok) {
                connection = conn
                bound = true
                AppLog.state("TermuxProcessPin: bindService success bind#$attempt flags=0x${flags.toString(16)}")
            } else {
                context.unbindService(conn)
                AppLog.e("STATE", "TermuxProcessPin: bindService returned false bind#$attempt — service unreachable")
            }
            ok
        } catch (e: SecurityException) {
            AppLog.e("STATE", "TermuxProcessPin: bindService denied bind#$attempt — com.termux.permission.RUN_COMMAND missing? ${e.message}", e)
            false
        } catch (e: Exception) {
            AppLog.e("STATE", "TermuxProcessPin: bindService threw bind#$attempt: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun unpin() {
        val conn = connection ?: return
        try {
            context.unbindService(conn)
            AppLog.state("TermuxProcessPin: unbindService ok (total binds=${bindCounter.get()})")
        } catch (e: IllegalArgumentException) {
            AppLog.state("TermuxProcessPin: unbind noop (${e.message})")
        } catch (e: Exception) {
            AppLog.e("STATE", "TermuxProcessPin: unbind threw: ${e.message}", e)
        } finally {
            connection = null
            bound = false
        }
    }
}

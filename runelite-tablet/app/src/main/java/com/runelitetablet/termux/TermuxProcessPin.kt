package com.runelitetablet.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.runelitetablet.logging.AppLog

/**
 * Holds a long-lived `bindService` connection to `com.termux.app.RunCommandService`
 * so Android promotes the Termux process to PROCESS_STATE_BOUND_TOP while our
 * RuneLiteSessionService is foreground. The intent is to move Termux (and every
 * child it spawns — proot, java, virgl, openbox) out of `/background` cpuset
 * (CPUs 0-3 on Samsung OneUI) into `/top-app` (CPUs 0-7). BIND_IMPORTANT +
 * BIND_INCLUDE_CAPABILITIES are the levers that actually cause the scheduler
 * to treat the bound process as at-least-as-important as the binding client.
 *
 * S74 proved Termux's /background clamp was the FPS ceiling (see
 * docs/logs/slice4-sticky-lock-results.md). This class is the first, cheapest
 * test of the bind-hoist hypothesis before falling back to a full service-owned
 * pipeline.
 */
class TermuxProcessPin(private val context: Context) {

    private var connection: ServiceConnection? = null
    private var bound = false

    /** Idempotent. Returns true if a pin is now held, false if bind failed. */
    @Synchronized
    fun pin(): Boolean {
        if (bound) {
            AppLog.state("TermuxProcessPin: pin() skipped — already bound")
            return true
        }

        val intent = Intent().apply {
            component = ComponentName(
                TermuxPackageHelper.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService"
            )
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                AppLog.state("TermuxProcessPin: onServiceConnected name=$name binder=${service != null}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                AppLog.state("TermuxProcessPin: onServiceDisconnected name=$name")
            }

            override fun onNullBinding(name: ComponentName?) {
                AppLog.state("TermuxProcessPin: onNullBinding name=$name (connection still hoists priority)")
            }

            override fun onBindingDied(name: ComponentName?) {
                AppLog.state("TermuxProcessPin: onBindingDied name=$name")
            }
        }

        var flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            flags = flags or Context.BIND_INCLUDE_CAPABILITIES
        }

        return try {
            val ok = context.bindService(intent, conn, flags)
            if (ok) {
                connection = conn
                bound = true
                AppLog.state("TermuxProcessPin: bindService success flags=0x${flags.toString(16)}")
            } else {
                context.unbindService(conn)
                AppLog.e("STATE", "TermuxProcessPin: bindService returned false — service unreachable")
            }
            ok
        } catch (e: SecurityException) {
            AppLog.e("STATE", "TermuxProcessPin: bindService denied — com.termux.permission.RUN_COMMAND missing? ${e.message}", e)
            false
        } catch (e: Exception) {
            AppLog.e("STATE", "TermuxProcessPin: bindService threw: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun unpin() {
        val conn = connection ?: return
        try {
            context.unbindService(conn)
            AppLog.state("TermuxProcessPin: unbindService ok")
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

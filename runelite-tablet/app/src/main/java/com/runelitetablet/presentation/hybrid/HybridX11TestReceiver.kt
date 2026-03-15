package com.runelitetablet.presentation.hybrid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only receiver for exercising the hybrid same-path bridge from adb.
 *
 * This lets adb trigger the test indirectly through our app process, which
 * already has com.termux.permission.RUN_COMMAND.
 */
class HybridX11TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val runner = TermuxCommandRunner(context)
        val stopExistingX11 = """
            for proc in /proc/[0-9]*; do
                pid=${'$'}{proc#/proc/}
                [ "${'$'}pid" = "${'$'}$" ] && continue
                [ -r "${'$'}proc/cmdline" ] || continue
                cmdline=${'$'}(tr '\0' ' ' < "${'$'}proc/cmdline" 2>/dev/null || true)
                case "${'$'}cmdline" in
                    *"com.termux.x11.Loader"*|*"termux-x11 :"*)
                        kill "${'$'}pid" 2>/dev/null || true
                        ;;
                esac
            done
        """.trimIndent()

        when (action) {
            ACTION_START_TEST -> {
                val pendingResult = goAsync()
                HybridX11Bridge.clear("start_requested")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = runner.execute(
                            commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                            arguments = arrayOf(
                                "-lc",
                                """
                                echo "[shell] start requested"
                                $stopExistingX11
                                echo "[shell] old x11 killed"
                                sleep 1
                                export TERMUX_X11_OVERRIDE_PACKAGE=com.runelitetablet
                                echo "[shell] override=${'$'}TERMUX_X11_OVERRIDE_PACKAGE"
                                echo "[shell] termux_x11_path=${'$'}(command -v termux-x11 || echo missing)"
                                echo "[shell] package_path=${'$'}(pm path com.termux.x11 2>/dev/null || echo missing)"
                                ${TermuxCommandRunner.TERMUX_BIN_PATH}/termux-x11 :0 > ${TermuxCommandRunner.TERMUX_HOME_PATH}/hybrid-x11-start.log 2>&1 &
                                child=${'$'}!
                                echo "[shell] child_pid=${'$'}child"
                                sleep 3
                                echo "[shell] ps child:"
                                ps -A | grep -F "${'$'}child" || true
                                echo "[shell] ps x11:"
                                ps -A | grep -E 'com.termux.x11.Loader|termux-x11' || true
                                """.trimIndent()
                            ),
                            background = true,
                            timeoutMs = 15_000L
                        )
                        AppLog.step(
                            "hybrid_x11",
                            "HybridX11TestReceiver: start test result exitCode=${result.exitCode} " +
                                "error=${result.error} stdout=${result.stdout ?: "<none>"} stderr=${result.stderr ?: "<none>"}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.e("HYBRID_X11", "HybridX11TestReceiver: start test failed: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_STOP_TEST -> {
                val launched = runner.launchBackground(
                    commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                    arguments = arrayOf(
                        "-lc",
                        """
                        $stopExistingX11
                        """.trimIndent()
                    )
                )
                HybridX11Bridge.clear("stop_requested")
                AppLog.step("hybrid_x11", "HybridX11TestReceiver: stop test dispatched launched=$launched")
            }
        }
    }

    companion object {
        const val ACTION_START_TEST = "com.runelitetablet.action.START_HYBRID_X11_TEST"
        const val ACTION_STOP_TEST = "com.runelitetablet.action.STOP_HYBRID_X11_TEST"
    }
}

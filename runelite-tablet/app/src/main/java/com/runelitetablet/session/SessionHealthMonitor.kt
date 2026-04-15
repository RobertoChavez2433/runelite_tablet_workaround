package com.runelitetablet.session

import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls Termux to check if RuneLite's Java process is alive.
 * Uses sentinel file ($PREFIX/tmp/.rlt-session-alive) created by launch-runelite.sh
 * and deleted when Java exits or shutdown-session.sh runs.
 *
 * Debounces: requires [STOPPED_THRESHOLD] consecutive "STOPPED" readings before
 * transitioning from Running to Stopped, to avoid flapping on transient health check failures.
 */
class SessionHealthMonitor(
    private val commandRunner: CommandRunner,
    private val scope: CoroutineScope
) {
    companion object {
        private const val STARTUP_POLL_INTERVAL_MS = 5_000L
        private const val RUNNING_POLL_INTERVAL_MS = 60_000L
        private const val HEALTH_CHECK_TIMEOUT_MS = 5_000L
        private const val STOPPED_THRESHOLD = 3
        private const val ERROR_THRESHOLD = 3
    }

    private var pollingJob: Job? = null
    private var consecutiveStoppedCount = 0
    private var consecutiveErrorCount = 0

    /**
     * Start polling. Calls [onStateChanged] with the detected session state.
     * Returns the polling [Job] which can be cancelled to stop polling.
     */
    fun startPolling(onStateChanged: (SessionState) -> Unit, initialDelayMs: Long = 0L): Job {
        stopPolling()
        consecutiveStoppedCount = 0
        consecutiveErrorCount = 0
        val job = scope.launch {
            if (initialDelayMs > 0) {
                AppLog.d("SESSION", "startPolling: waiting ${initialDelayMs}ms for launch sequence before first poll")
                delay(initialDelayMs)
            }
            while (isActive) {
                val rawState = checkHealth()
                val emittedState = debounce(rawState)
                if (emittedState != null) {
                    onStateChanged(emittedState)
                }
                val nextPollDelayMs = if (rawState is SessionState.Running) {
                    RUNNING_POLL_INTERVAL_MS
                } else {
                    STARTUP_POLL_INTERVAL_MS
                }
                delay(nextPollDelayMs)
            }
        }
        pollingJob = job
        return job
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        consecutiveStoppedCount = 0
        consecutiveErrorCount = 0
    }

    /**
     * Single health check. Can be called independently for immediate checks.
     */
    suspend fun checkHealth(): SessionState {
        return try {
            val result = commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", """
                    SESSION_ALIVE="no"
                    VIRGL_STATUS="n/a"
                    if [ -f "${'$'}PREFIX/tmp/.rlt-session-alive" ]; then
                        SESSION_ALIVE="yes"
                    fi
                    # Check VirGL process health if PID file exists
                    ACTIVE_SESSION_DIR="${'$'}HOME/.rlt-session"
                    if [ -f "${'$'}ACTIVE_SESSION_DIR/virgl.pid" ]; then
                        VPID=${'$'}(cat "${'$'}ACTIVE_SESSION_DIR/virgl.pid" 2>/dev/null)
                        if [ -n "${'$'}VPID" ] && kill -0 "${'$'}VPID" 2>/dev/null; then
                            VIRGL_STATUS="alive:pid=${'$'}VPID"
                        elif [ -f "${'$'}ACTIVE_SESSION_DIR/virgl.status" ]; then
                            VIRGL_STATUS="dead:${'$'}(cat "${'$'}ACTIVE_SESSION_DIR/virgl.status" 2>/dev/null)"
                        else
                            VIRGL_STATUS="dead:pid=${'$'}VPID"
                        fi
                    fi
                    echo "session=${'$'}SESSION_ALIVE virgl=${'$'}VIRGL_STATUS"
                """.trimIndent()),
                background = true,
                timeoutMs = HEALTH_CHECK_TIMEOUT_MS
            )
            val output = result.stdout?.trim() ?: ""
            val sessionAlive = output.contains("session=yes")
            val virglDead = output.contains("virgl=dead")
            if (virglDead) {
                val virglInfo = output.substringAfter("virgl=", "unknown")
                AppLog.w("SESSION", "checkHealth: VirGL server died ($virglInfo) — GPU rendering degraded")
            }
            when {
                sessionAlive -> SessionState.Running
                !sessionAlive && output.contains("session=no") -> SessionState.Stopped
                else -> SessionState.Error("Health check: unexpected output: $output")
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.w("SESSION", "checkHealth: timeout after ${HEALTH_CHECK_TIMEOUT_MS}ms")
            SessionState.Error("Health check timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AppLog.w("SESSION", "checkHealth: SecurityException — Termux unavailable: ${e.message}")
            SessionState.Error("Termux unavailable")
        } catch (e: Exception) {
            AppLog.w("SESSION", "checkHealth: exception: ${e.message}")
            SessionState.Error("Health check failed: ${e.message}")
        }
    }

    /**
     * Debounce logic: require multiple consecutive readings before state transitions.
     * Returns the state to emit, or null to keep previous state.
     */
    private fun debounce(rawState: SessionState): SessionState? {
        return when (rawState) {
            is SessionState.Running -> {
                consecutiveStoppedCount = 0
                consecutiveErrorCount = 0
                rawState
            }
            is SessionState.Stopped -> {
                consecutiveErrorCount = 0
                consecutiveStoppedCount++
                if (consecutiveStoppedCount >= STOPPED_THRESHOLD) {
                    rawState
                } else {
                    AppLog.d("SESSION", "debounce: stopped count=$consecutiveStoppedCount/$STOPPED_THRESHOLD, keeping previous state")
                    null // Keep previous state
                }
            }
            is SessionState.Error -> {
                consecutiveStoppedCount = 0
                consecutiveErrorCount++
                if (consecutiveErrorCount >= ERROR_THRESHOLD) {
                    rawState
                } else {
                    AppLog.d("SESSION", "debounce: error count=$consecutiveErrorCount/$ERROR_THRESHOLD, keeping previous state")
                    null // Keep previous state
                }
            }
            else -> rawState
        }
    }
}

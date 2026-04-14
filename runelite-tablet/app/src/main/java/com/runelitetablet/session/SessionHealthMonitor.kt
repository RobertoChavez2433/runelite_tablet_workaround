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
    fun startPolling(onStateChanged: (SessionState) -> Unit): Job {
        stopPolling()
        consecutiveStoppedCount = 0
        consecutiveErrorCount = 0
        val job = scope.launch {
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
                    if [ -f "${'$'}PREFIX/tmp/.rlt-session-alive" ]; then
                        echo "RUNNING"
                    else
                        echo "STOPPED"
                    fi
                """.trimIndent()),
                background = true,
                timeoutMs = HEALTH_CHECK_TIMEOUT_MS
            )
            when (result.stdout?.trim()) {
                "RUNNING" -> SessionState.Running
                "STOPPED" -> SessionState.Stopped
                else -> SessionState.Error("Health check: unexpected output")
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

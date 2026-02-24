package com.runelitetablet.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.runelitetablet.MainActivity
import com.runelitetablet.PendingIntentCompat
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import com.runelitetablet.setup.ScriptManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service that owns the RuneLite game session lifecycle.
 *
 * Communication: Uses companion object MutableStateFlow (same-process, no binding needed).
 * Health monitoring: Polls Termux every 15s via SessionHealthMonitor.
 * Notification: Shows "RuneLite is running" with Switch/Stop actions.
 * onTaskRemoved: Keeps running (by design — user swiping app should not kill RuneLite).
 */
class RuneLiteSessionService : Service() {

    companion object {
        const val ACTION_START_SESSION = "com.runelitetablet.session.START"
        const val ACTION_STOP_SESSION = "com.runelitetablet.session.STOP"
        const val ACTION_SWITCH_TO_GAME = "com.runelitetablet.session.SWITCH"
        const val ACTION_CHECK_SESSION = "com.runelitetablet.session.CHECK"

        private val NOTIFICATION_CHANNEL_ID = RuneLiteTabletApp.CHANNEL_SESSION
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "runelite_session_prefs"
        private const val PREF_SESSION_ACTIVE = "session_active"
        private const val PREF_SESSION_START_TIME = "session_start_time"

        private const val SHUTDOWN_TIMEOUT_MS = 15_000L

        private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
        val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

        private val _sessionStartTime = MutableStateFlow(0L)
        val sessionStartTime: StateFlow<Long> = _sessionStartTime.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var healthMonitor: SessionHealthMonitor
    private lateinit var commandRunner: TermuxCommandRunner
    private lateinit var scriptManager: ScriptManager
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        AppLog.lifecycle("RuneLiteSessionService.onCreate")
        commandRunner = TermuxCommandRunner(applicationContext)
        scriptManager = ScriptManager(applicationContext, commandRunner)
        healthMonitor = SessionHealthMonitor(commandRunner, serviceScope)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        AppLog.lifecycle("RuneLiteSessionService.onStartCommand: action=$action startId=$startId")

        when (action) {
            ACTION_START_SESSION -> handleStartSession()
            ACTION_STOP_SESSION -> handleStopSession()
            ACTION_SWITCH_TO_GAME -> handleSwitchToGame()
            ACTION_CHECK_SESSION -> handleCheckSession()
            null -> {
                // Service restarted by system (START_STICKY). Check if session is still alive.
                AppLog.lifecycle("RuneLiteSessionService: restarted by system, checking session")
                handleCheckSession()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep running — user swiping the app should not kill RuneLite
        AppLog.lifecycle("RuneLiteSessionService.onTaskRemoved: keeping service alive")
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.lifecycle("RuneLiteSessionService.onDestroy")
        healthMonitor.stopPolling()
        serviceScope.cancel()
        // Do NOT clear companion state — static fields outlive service instances
    }

    // -------------------------------------------------------------------------
    // Action handlers
    // -------------------------------------------------------------------------

    private fun handleStartSession() {
        if (_sessionState.value is SessionState.Running || _sessionState.value is SessionState.Starting) {
            AppLog.lifecycle("RuneLiteSessionService: session already running/starting, ignoring START")
            return
        }

        _sessionState.value = SessionState.Starting
        val startTime = System.currentTimeMillis()
        _sessionStartTime.value = startTime

        // Persist to SharedPreferences for crash recovery
        prefs.edit().apply {
            putBoolean(PREF_SESSION_ACTIVE, true)
            putLong(PREF_SESSION_START_TIME, startTime)
            apply()
        }

        // Show foreground notification immediately (must be within 5 seconds)
        startForeground(NOTIFICATION_ID, buildNotification("Starting RuneLite..."))

        // Start health polling — it will detect when RuneLite actually starts
        healthMonitor.startPolling { state ->
            handleHealthStateChange(state)
        }

        AppLog.lifecycle("RuneLiteSessionService: session started, health polling active")
    }

    private fun handleStopSession() {
        AppLog.lifecycle("RuneLiteSessionService: stopping session")
        _sessionState.value = SessionState.Stopped

        healthMonitor.stopPolling()

        // Run shutdown script
        serviceScope.launch {
            try {
                val deployed = scriptManager.deployScripts()
                if (deployed) {
                    val result = commandRunner.execute(
                        commandPath = scriptManager.getScriptPath("shutdown-session.sh"),
                        background = true,
                        timeoutMs = SHUTDOWN_TIMEOUT_MS
                    )
                    if (result.stdout?.contains("SHUTDOWN_COMPLETE") == true) {
                        AppLog.lifecycle("RuneLiteSessionService: graceful shutdown complete")
                    } else {
                        AppLog.w("SESSION", "RuneLiteSessionService: shutdown script did not complete cleanly: exitCode=${result.exitCode}")
                        // Fallback: aggressive pkill
                        aggressiveShutdown()
                    }
                } else {
                    AppLog.w("SESSION", "RuneLiteSessionService: script deploy failed, using aggressive shutdown")
                    aggressiveShutdown()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.e("SESSION", "RuneLiteSessionService: shutdown exception: ${e.message}", e)
                aggressiveShutdown()
            } finally {
                clearSessionState()
                stopSelf()
            }
        }
    }

    private fun handleSwitchToGame() {
        AppLog.lifecycle("RuneLiteSessionService: switching to Termux:X11")
        val x11Intent = packageManager.getLaunchIntentForPackage(TermuxPackageHelper.TERMUX_X11_PACKAGE)
        if (x11Intent != null) {
            x11Intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(x11Intent)
        } else {
            AppLog.w("SESSION", "RuneLiteSessionService: cannot launch Termux:X11 — not installed")
        }
    }

    private fun handleCheckSession() {
        // Must call startForeground when service is restarted by system (START_STICKY)
        // to avoid ForegroundServiceDidNotStartInTimeException on Android 12+
        startForeground(NOTIFICATION_ID, buildNotification("Checking session..."))

        serviceScope.launch {
            val state = healthMonitor.checkHealth()
            when (state) {
                is SessionState.Running -> {
                    _sessionState.value = SessionState.Running
                    // Recover start time from prefs if we don't have it
                    if (_sessionStartTime.value == 0L) {
                        _sessionStartTime.value = prefs.getLong(PREF_SESSION_START_TIME, System.currentTimeMillis())
                    }
                    updateNotification("RuneLite is running")
                    // Start polling if not already
                    healthMonitor.startPolling { s -> handleHealthStateChange(s) }
                }
                is SessionState.Stopped, is SessionState.Error -> {
                    // Nothing is running — clear state and stop service
                    AppLog.lifecycle("RuneLiteSessionService: check found no running session, stopping")
                    _sessionState.value = SessionState.Idle
                    clearSessionState()
                    stopSelf()
                }
                else -> {
                    // Idle/Starting — should not happen during check, but handle gracefully
                    AppLog.lifecycle("RuneLiteSessionService: check returned $state, stopping")
                    clearSessionState()
                    stopSelf()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Health state changes
    // -------------------------------------------------------------------------

    private fun handleHealthStateChange(state: SessionState) {
        when (state) {
            is SessionState.Running -> {
                if (_sessionState.value !is SessionState.Running) {
                    AppLog.lifecycle("RuneLiteSessionService: health monitor confirms RUNNING")
                }
                _sessionState.value = SessionState.Running
                val elapsed = formatElapsedTime()
                updateNotification("Playing for $elapsed")
            }
            is SessionState.Stopped -> {
                AppLog.lifecycle("RuneLiteSessionService: health monitor reports STOPPED")
                _sessionState.value = SessionState.Stopped
                updateNotification("RuneLite has stopped")
                healthMonitor.stopPolling()
                clearSessionState()
                stopSelf()
            }
            is SessionState.Error -> {
                val msg = (state as SessionState.Error).message
                AppLog.w("SESSION", "RuneLiteSessionService: health error: $msg")
                _sessionState.value = state
                updateNotification("Error: $msg")
                healthMonitor.stopPolling()
                clearSessionState()
                stopSelf()
            }
            else -> {} // Idle, Starting — not expected from health monitor
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(contentText: String): Notification {
        // Tap notification -> open our app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAGS
        )

        // "Switch to Game" action
        val switchIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RuneLiteSessionService::class.java).apply {
                action = ACTION_SWITCH_TO_GAME
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAGS
        )

        // "Stop Game" action
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, RuneLiteSessionService::class.java).apply {
                action = ACTION_STOP_SESSION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAGS
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RuneLite is running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                null, "Switch to Game", switchIntent
            ).build())
            .addAction(Notification.Action.Builder(
                null, "Stop Game", stopIntent
            ).build())
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun clearSessionState() {
        prefs.edit().apply {
            putBoolean(PREF_SESSION_ACTIVE, false)
            remove(PREF_SESSION_START_TIME)
            apply()
        }
        _sessionStartTime.value = 0L
    }

    private fun formatElapsedTime(): String {
        val startTime = _sessionStartTime.value
        if (startTime == 0L) return "0 min"
        val elapsedMs = System.currentTimeMillis() - startTime
        val minutes = (elapsedMs / 60_000).toInt()
        return if (minutes < 60) {
            "$minutes min"
        } else {
            val hours = minutes / 60
            val remainingMin = minutes % 60
            "${hours}h ${remainingMin}m"
        }
    }

    private suspend fun aggressiveShutdown() {
        AppLog.w("SESSION", "RuneLiteSessionService: running aggressive shutdown")
        try {
            commandRunner.execute(
                commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", """
                    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
                    pkill -f 'RuneLite.jar' 2>/dev/null || true
                    pkill -f 'openbox' 2>/dev/null || true
                    pkill -f 'proot-distro' 2>/dev/null || true
                    pkill -f 'proot --' 2>/dev/null || true
                    pulseaudio --kill 2>/dev/null || true
                    pkill -f 'termux-x11' 2>/dev/null || true
                    rm -f "${'$'}HOME/.rlt-session.pid" 2>/dev/null || true
                """.trimIndent()),
                background = true,
                timeoutMs = 10_000L
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.e("SESSION", "aggressiveShutdown: exception: ${e.message}", e)
        }
    }
}

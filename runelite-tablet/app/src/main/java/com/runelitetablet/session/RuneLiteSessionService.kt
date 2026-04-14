package com.runelitetablet.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.logging.AppLog
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.termux.TermuxCommandRunner
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

class RuneLiteSessionService : Service() {

    companion object {
        const val ACTION_START_SESSION = "com.runelitetablet.session.START"
        const val ACTION_STOP_SESSION = "com.runelitetablet.session.STOP"
        const val ACTION_SWITCH_TO_GAME = "com.runelitetablet.session.SWITCH"
        const val ACTION_CHECK_SESSION = "com.runelitetablet.session.CHECK"
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
    private lateinit var notificationHelper: SessionNotificationHelper
    private val presentationBackend = PresentationBackends.stable

    override fun onCreate() {
        super.onCreate()
        commandRunner = TermuxCommandRunner(applicationContext, (application as RuneLiteTabletApp).container.termux.resultRegistry)
        scriptManager = ScriptManager(applicationContext, commandRunner)
        healthMonitor = SessionHealthMonitor(commandRunner, serviceScope)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        notificationHelper = SessionNotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> handleStartSession()
            ACTION_STOP_SESSION -> handleStopSession()
            ACTION_SWITCH_TO_GAME -> handleSwitchToGame()
            ACTION_CHECK_SESSION -> handleCheckSession()
            null -> handleCheckSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        super.onDestroy()
        healthMonitor.stopPolling(); serviceScope.cancel()
    }

    private fun handleStartSession() {
        if (_sessionState.value is SessionState.Running || _sessionState.value is SessionState.Starting) return
        _sessionState.value = SessionState.Starting
        val startTime = System.currentTimeMillis()
        _sessionStartTime.value = startTime
        prefs.edit().putBoolean(PREF_SESSION_ACTIVE, true).putLong(PREF_SESSION_START_TIME, startTime).apply()
        startForeground(SessionNotificationHelper.NOTIFICATION_ID, notificationHelper.buildNotification("Starting RuneLite..."))
        healthMonitor.startPolling { handleHealthStateChange(it) }
    }

    private fun handleStopSession() {
        _sessionState.value = SessionState.Stopped; healthMonitor.stopPolling()
        serviceScope.launch {
            try {
                if (scriptManager.deployScripts()) {
                    val result = commandRunner.execute(commandPath = scriptManager.getScriptPath("shutdown-session.sh"), background = true, timeoutMs = SHUTDOWN_TIMEOUT_MS)
                    if (result.stdout?.contains("SHUTDOWN_COMPLETE") != true) aggressiveShutdown()
                } else aggressiveShutdown()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { aggressiveShutdown() }
            finally { clearSessionState(); stopSelf() }
        }
    }

    private fun handleSwitchToGame() {
        val intent = presentationBackend.createSwitchIntent(this)
        if (intent != null) startActivity(intent)
    }

    private fun handleCheckSession() {
        startForeground(SessionNotificationHelper.NOTIFICATION_ID, notificationHelper.buildNotification("Checking session..."))
        serviceScope.launch {
            when (healthMonitor.checkHealth()) {
                is SessionState.Running -> {
                    _sessionState.value = SessionState.Running
                    if (_sessionStartTime.value == 0L) _sessionStartTime.value = prefs.getLong(PREF_SESSION_START_TIME, System.currentTimeMillis())
                    notificationHelper.updateIfChanged("RuneLite is running")
                    healthMonitor.startPolling { handleHealthStateChange(it) }
                }
                else -> { _sessionState.value = SessionState.Idle; clearSessionState(); stopSelf() }
            }
        }
    }

    private fun handleHealthStateChange(state: SessionState) {
        when (state) {
            is SessionState.Running -> {
                _sessionState.value = SessionState.Running
                notificationHelper.updateIfChanged("Playing for ${formatElapsedTime()}")
            }
            is SessionState.Stopped, is SessionState.Error -> {
                _sessionState.value = state
                healthMonitor.stopPolling(); clearSessionState(); stopSelf()
            }
            else -> {}
        }
    }

    private fun clearSessionState() {
        prefs.edit().putBoolean(PREF_SESSION_ACTIVE, false).remove(PREF_SESSION_START_TIME).apply()
        _sessionStartTime.value = 0L
    }

    private fun formatElapsedTime(): String {
        val s = _sessionStartTime.value; if (s == 0L) return "0 min"
        val m = ((System.currentTimeMillis() - s) / 60_000).toInt()
        return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
    }

    private suspend fun aggressiveShutdown() {
        try {
            commandRunner.execute(
                commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                arguments = arrayOf("-c", """
                    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
                    pkill -f 'RuneLite.jar' 2>/dev/null || true
                    pkill -f 'openbox' 2>/dev/null || true
                    pkill -f 'proot-distro' 2>/dev/null || true
                    pkill -f 'proot --' 2>/dev/null || true
                    pulseaudio --kill 2>/dev/null || true
                    pkill -f 'virgl_test_server' 2>/dev/null || true
                    pkill -f 'termux-x11' 2>/dev/null || true
                    rm -f "${'$'}HOME/.rlt-session.pid" "${'$'}PREFIX/tmp/.rlt-session-alive" "${'$'}HOME/.rlt-launch-env.sh" 2>/dev/null || true
                """.trimIndent()),
                background = true, timeoutMs = 10_000L
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AppLog.e("SESSION", "aggressiveShutdown: ${e.message}", e) }
    }
}

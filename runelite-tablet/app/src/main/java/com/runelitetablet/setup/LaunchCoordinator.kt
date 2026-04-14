package com.runelitetablet.setup

import android.content.Context
import android.content.Intent
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.auth.LaunchEnvDeployer
import com.runelitetablet.auth.SessionValidation
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.domain.auth.CredentialStore
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.ScriptDeployer
import com.runelitetablet.presentation.PresentationBackend
import com.runelitetablet.session.RuneLiteSessionService
import com.runelitetablet.session.SessionState
import com.runelitetablet.ui.DisplayPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LaunchCoordinator(
    private val context: Context,
    private val commandRunner: CommandRunner,
    private val packageChecker: PackageChecker,
    private val scriptDeployer: ScriptDeployer,
    private val credentialStore: CredentialStore,
    private val oAuth2Manager: JagexOAuth2Manager,
    private val orchestrator: SetupOrchestrator,
    private val displayPreferences: DisplayPreferences,
    private val presentationBackend: PresentationBackend,
    private val authCoordinator: AuthCoordinator,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launchState: StateFlow<LaunchState> = _launchState.asStateFlow()
    private val _healthStatus = MutableStateFlow<HealthCheckResult?>(null)
    val healthStatus: StateFlow<HealthCheckResult?> = _healthStatus.asStateFlow()
    private val _showHealthDialog = MutableStateFlow<List<String>?>(null)
    val showHealthDialog: StateFlow<List<String>?> = _showHealthDialog.asStateFlow()
    private var activeLaunchJob: Job? = null
    private var x11ReadySwitchJob: Job? = null

    fun launchRuneLite() {
        if (activeLaunchJob?.isActive == true) return
        val s = RuneLiteSessionService.sessionState.value
        if (s is SessionState.Running || s is SessionState.Starting) return
        activeLaunchJob = scope.launch {
            try {
                _launchState.value = LaunchState.CheckingUpdate
                parseUpdateOutput(runUpdateCheck())
                _launchState.value = LaunchState.CheckingHealth
                val health = runHealthCheck()
                _healthStatus.value = health
                if (health is HealthCheckResult.Degraded) {
                    _launchState.value = LaunchState.Failed("Setup incomplete: ${health.failures.joinToString()}")
                    _showHealthDialog.value = health.failures; return@launch
                }
                performLaunch()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _launchState.value = LaunchState.Failed(e.message ?: "Unknown error") }
            finally { activeLaunchJob = null }
        }
    }

    suspend fun performLaunch() {
        val hasCredentials = withContext(ioDispatcher) { credentialStore.hasCredentials() }
        if (hasCredentials) {
            _launchState.value = LaunchState.RefreshingTokens
            when (authCoordinator.refreshIfNeeded()) {
                is AuthResult.NeedsLogin -> {
                    authCoordinator.pendingLaunchAfterAuth.set(true)
                    _launchState.value = LaunchState.Idle; authCoordinator.startLogin(); return
                }
                is AuthResult.NetworkError -> {}
                else -> {}
            }
            val sessionId = withContext(ioDispatcher) { credentialStore.getCredentials() }?.sessionId
            if (sessionId != null && sessionId.isNotEmpty()) {
                _launchState.value = LaunchState.ValidatingSession
                when (oAuth2Manager.validateSession(sessionId)) {
                    is SessionValidation.Valid -> {}
                    is SessionValidation.Expired -> {
                        authCoordinator.pendingLaunchAfterAuth.set(true)
                        withContext(ioDispatcher) { credentialStore.clearCredentials() }
                        if (orchestrator.actions == null) {
                            authCoordinator.pendingLaunchAfterAuth.set(false)
                            _launchState.value = LaunchState.Failed("Activity not available"); return
                        }
                        _launchState.value = LaunchState.Idle; authCoordinator.startLogin(); return
                    }
                    is SessionValidation.NetworkError -> {}
                }
            }
        }
        _launchState.value = LaunchState.Launching
        val envFilePath = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner)
        val success = launchInternal(envFilePath)
        if (success) { _launchState.value = LaunchState.Idle; startSessionService() }
        else { _launchState.value = LaunchState.Failed("Failed to start RuneLite launch command") }
    }

    private suspend fun launchInternal(envFilePath: String? = null): Boolean {
        if (!presentationBackend.isInstalled(packageChecker)) return false
        presentationBackend.applyLaunchPreferences(context, displayPreferences)
        val presentationIntent = presentationBackend.createLaunchIntent(context)
        if (presentationIntent != null && presentationBackend.shouldForegroundBeforeBootstrap()) {
            orchestrator.actions?.launchIntent(presentationIntent); delay(700)
        }
        val scriptPath = scriptDeployer.getScriptPath("launch-runelite.sh")
        val arguments = if (envFilePath != null) arrayOf(envFilePath) else null
        val success = commandRunner.launchBackground(commandPath = scriptPath, arguments = arguments)
        if (success && presentationIntent != null && presentationBackend.shouldWaitForReadySignal()) {
            waitForDisplayReadyAndSwitch(presentationIntent)
        }
        return success
    }

    private fun waitForDisplayReadyAndSwitch(x11LaunchIntent: Intent) {
        x11ReadySwitchJob?.cancel()
        x11ReadySwitchJob = scope.launch {
            repeat(20) {
                val result = commandRunner.execute(
                    commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
                    arguments = arrayOf("-c", """
                        CURRENT_FILE="${'$'}PREFIX/tmp/rlt-session/current"
                        if [ -f "${'$'}CURRENT_FILE" ]; then
                            SESSION_DIR="$(cat "${'$'}CURRENT_FILE" 2>/dev/null)"
                            if [ -n "${'$'}SESSION_DIR" ] && [ -f "${'$'}SESSION_DIR/display.ready" ]; then echo READY; fi
                        fi
                    """.trimIndent()),
                    background = true, timeoutMs = CommandRunner.TIMEOUT_VERIFY_MS
                )
                if (result.stdout?.contains("READY") == true) {
                    orchestrator.actions?.launchIntent(x11LaunchIntent); return@launch
                }
                delay(500)
            }
        }
    }

    private suspend fun runUpdateCheck(): String = try {
        if (!scriptDeployer.deployScripts()) ""
        else commandRunner.execute(commandPath = scriptDeployer.getScriptPath("update-runelite.sh"), background = true, timeoutMs = 60_000L).stdout ?: ""
    } catch (e: TimeoutCancellationException) { "" } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }

    private fun parseUpdateOutput(output: String) {
        val statusLine = output.lines().lastOrNull { it.startsWith("UPDATE_STATUS") } ?: ""
        if (statusLine.contains("downloading")) {
            val parts = statusLine.removePrefix("UPDATE_STATUS downloading ").split(" -> ")
            _launchState.value = LaunchState.Updating(parts.getOrElse(0) { "?" }, parts.getOrElse(1) { "?" })
        }
    }

    private suspend fun runHealthCheck(): HealthCheckResult = try {
        if (!scriptDeployer.deployScripts()) HealthCheckResult.Inconclusive
        else {
            val output = commandRunner.execute(commandPath = scriptDeployer.getScriptPath("health-check.sh"), background = true, timeoutMs = 10_000L).stdout ?: ""
            val failures = output.lines().filter { it.startsWith("HEALTH") && it.contains("FAIL") }
                .map { line -> val p = line.split(" "); "${p.getOrElse(1) { "unknown" }}: ${p.getOrElse(3) { "unknown" }}" }
            if (failures.isEmpty()) HealthCheckResult.Healthy else HealthCheckResult.Degraded(failures)
        }
    } catch (e: TimeoutCancellationException) { HealthCheckResult.Inconclusive } catch (e: CancellationException) { throw e } catch (_: Exception) { HealthCheckResult.Inconclusive }

    private fun startSessionService() {
        if (!orchestrator.hasNotificationPermission()) orchestrator.actions?.requestNotificationPermission()
        context.startForegroundService(Intent(context, RuneLiteSessionService::class.java).apply {
            action = RuneLiteSessionService.ACTION_START_SESSION
        })
    }

    fun stopSession() {
        context.startService(Intent(context, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_STOP_SESSION })
    }

    fun switchToGame() {
        context.startService(Intent(context, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_SWITCH_TO_GAME })
    }

    fun checkSession() {
        val state = RuneLiteSessionService.sessionState.value
        if (state is SessionState.Running || state is SessionState.Starting) {
            context.startService(Intent(context, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_CHECK_SESSION })
        }
    }

    fun dismissHealthDialog() { _showHealthDialog.value = null }
    fun launchAnyway() { _showHealthDialog.value = null; scope.launch { performLaunch() } }
}

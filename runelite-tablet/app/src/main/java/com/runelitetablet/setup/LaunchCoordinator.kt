package com.runelitetablet.setup

import android.content.Context
import android.content.Intent
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.domain.logging.CorrelationId
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
        val corrId = CorrelationId.generate("launch")
        logger?.state("LaunchCoordinator.launchRuneLite: starting pre-launch sequence", corrId)
        activeLaunchJob = scope.launch {
            try {
                _launchState.value = LaunchState.CheckingUpdate
                logger?.state("LaunchCoordinator: checking for updates", correlationId = corrId)
                parseUpdateOutput(runUpdateCheck())
                _launchState.value = LaunchState.CheckingHealth
                logger?.state("LaunchCoordinator: running health check", correlationId = corrId)
                val health = runHealthCheck(correlationId = corrId)
                _healthStatus.value = health
                if (health is HealthCheckResult.Degraded) {
                    logger?.w("LAUNCH", "LaunchCoordinator: health check degraded failures=${health.failures}", correlationId = corrId)
                    _launchState.value = LaunchState.Failed("Setup incomplete: ${health.failures.joinToString()}")
                    _showHealthDialog.value = health.failures; return@launch
                }
                performLaunch(correlationId = corrId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { logger?.e("LAUNCH", "LaunchCoordinator: launch error", throwable = e, correlationId = corrId); _launchState.value = LaunchState.Failed(e.message ?: "Unknown error") }
            finally { activeLaunchJob = null }
        }
    }

    suspend fun performLaunch(correlationId: String? = null) {
        logger?.state("LaunchCoordinator.performLaunch: starting", correlationId = correlationId)
        val hasCredentials = withContext(ioDispatcher) { credentialStore.hasCredentials() }
        logger?.state("LaunchCoordinator: hasCredentials=$hasCredentials", correlationId = correlationId)
        if (hasCredentials) {
            _launchState.value = LaunchState.RefreshingTokens
            val authCorrId = if (correlationId != null) CorrelationId.nested(correlationId, "auth-refresh") else null
            logger?.state("LaunchCoordinator: refreshing auth tokens parentCorrId=$correlationId childCorrId=$authCorrId", correlationId = correlationId)
            when (val authResult = authCoordinator.refreshIfNeeded(correlationId = authCorrId)) {
                is AuthResult.NeedsLogin -> {
                    logger?.w("LAUNCH", "LaunchCoordinator: auth needs login, redirecting", correlationId = correlationId)
                    authCoordinator.pendingLaunchAfterAuth.set(true)
                    _launchState.value = LaunchState.Idle; authCoordinator.startLogin(); return
                }
                is AuthResult.NetworkError -> {
                    logger?.w("LAUNCH", "LaunchCoordinator: auth refresh network error, continuing", correlationId = correlationId)
                }
                else -> {
                    logger?.state("LaunchCoordinator: auth refresh result=$authResult", correlationId = correlationId)
                }
            }
            val sessionId = withContext(ioDispatcher) { credentialStore.getCredentials() }?.sessionId
            if (sessionId != null && sessionId.isNotEmpty()) {
                _launchState.value = LaunchState.ValidatingSession
                logger?.state("LaunchCoordinator: validating session", correlationId = correlationId)
                when (oAuth2Manager.validateSession(sessionId)) {
                    is SessionValidation.Valid -> {
                        logger?.state("LaunchCoordinator: session valid", correlationId = correlationId)
                    }
                    is SessionValidation.Expired -> {
                        logger?.w("LAUNCH", "LaunchCoordinator: session expired, redirecting to login", correlationId = correlationId)
                        authCoordinator.pendingLaunchAfterAuth.set(true)
                        withContext(ioDispatcher) { credentialStore.clearCredentials() }
                        if (orchestrator.actions == null) {
                            authCoordinator.pendingLaunchAfterAuth.set(false)
                            _launchState.value = LaunchState.Failed("Activity not available"); return
                        }
                        _launchState.value = LaunchState.Idle; authCoordinator.startLogin(); return
                    }
                    is SessionValidation.NetworkError -> {
                        logger?.w("LAUNCH", "LaunchCoordinator: session validation network error, continuing", correlationId = correlationId)
                    }
                }
            }
        }
        _launchState.value = LaunchState.Launching
        logger?.state("LaunchCoordinator: deploying env file backend=${presentationBackend.id}", correlationId = correlationId)
        val envFilePath = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner)
        logger?.state("LaunchCoordinator: envFilePath=$envFilePath hasEnvFile=${envFilePath != null}", correlationId = correlationId)
        logger?.d("LAUNCH", "LaunchCoordinator: env contents=[tokens redacted] backend=${presentationBackend.id}", correlationId = correlationId)
        val success = launchInternal(envFilePath, correlationId)
        if (success) {
            logger?.state("LaunchCoordinator: launch succeeded, starting session service", correlationId = correlationId)
            _launchState.value = LaunchState.Idle
            startSessionService(correlationId)
        } else {
            logger?.e("LAUNCH", "LaunchCoordinator: launch failed", correlationId = correlationId)
            _launchState.value = LaunchState.Failed("Failed to start RuneLite launch command")
        }
    }

    private suspend fun launchInternal(envFilePath: String? = null, correlationId: String? = null): Boolean {
        if (!presentationBackend.isInstalled(packageChecker)) {
            logger?.w("LAUNCH", "launchInternal: backend ${presentationBackend.id} not installed", correlationId = correlationId)
            return false
        }
        presentationBackend.applyLaunchPreferences(context, displayPreferences)
        val presentationIntent = presentationBackend.createLaunchIntent(context)
        if (presentationIntent != null && presentationBackend.shouldForegroundBeforeBootstrap()) {
            logger?.d("LAUNCH", "launchInternal: foregrounding ${presentationBackend.id} before bootstrap", correlationId = correlationId)
            orchestrator.actions?.launchIntent(presentationIntent); delay(700)
        }
        val scriptPath = scriptDeployer.getScriptPath("launch-runelite.sh")
        val arguments = if (envFilePath != null) arrayOf(envFilePath) else null
        logger?.state("launchInternal: dispatching command scriptPath=$scriptPath envFile=$envFilePath backend=${presentationBackend.id}", correlationId = correlationId)
        val success = commandRunner.launchBackground(commandPath = scriptPath, arguments = arguments)
        logger?.state("launchInternal: command dispatched success=$success", correlationId = correlationId)
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

    private suspend fun runHealthCheck(correlationId: String? = null): HealthCheckResult = try {
        if (!scriptDeployer.deployScripts()) HealthCheckResult.Inconclusive
        else {
            val output = commandRunner.execute(commandPath = scriptDeployer.getScriptPath("health-check.sh"), background = true, timeoutMs = 10_000L).stdout ?: ""
            val failures = output.lines().filter { it.startsWith("HEALTH") && it.contains("FAIL") }
                .map { line -> val p = line.split(" "); "${p.getOrElse(1) { "unknown" }}: ${p.getOrElse(3) { "unknown" }}" }
            if (failures.isEmpty()) HealthCheckResult.Healthy else HealthCheckResult.Degraded(failures)
        }
    } catch (e: TimeoutCancellationException) { HealthCheckResult.Inconclusive } catch (e: CancellationException) { throw e } catch (_: Exception) { HealthCheckResult.Inconclusive }

    private fun startSessionService(correlationId: String? = null) {
        logger?.state("startSessionService: starting foreground service", correlationId = correlationId)
        if (!orchestrator.hasNotificationPermission()) orchestrator.actions?.requestNotificationPermission()
        context.startForegroundService(Intent(context, RuneLiteSessionService::class.java).apply {
            action = RuneLiteSessionService.ACTION_START_SESSION
        })
        logger?.state("startSessionService: session service start confirmed", correlationId = correlationId)
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

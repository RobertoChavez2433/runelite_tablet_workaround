package com.runelitetablet.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.auth.CredentialManager
import com.runelitetablet.auth.GameCharacter
import com.runelitetablet.auth.GeckoAuthActivity
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.auth.OAuthException
import com.runelitetablet.auth.SessionValidation
import com.runelitetablet.auth.PkceHelper
import com.runelitetablet.auth.TokenResponse
import com.runelitetablet.cleanup.CleanupManager
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.logging.AppLog
import com.runelitetablet.presentation.PresentationBackend
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.session.RuneLiteSessionService
import com.runelitetablet.session.SessionState
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import com.runelitetablet.ui.DisplayPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents the current top-level screen the app should display.
 */
sealed class AppScreen {
    /** Setup wizard is running (installing Termux, proot, etc.) */
    object Setup : AppScreen()
    /** "Sign in with Jagex" login screen */
    object Login : AppScreen()
    /** Character selection screen (multiple characters on account) */
    data class CharacterSelect(val characters: List<GameCharacter>) : AppScreen()
    /** Ready to launch RuneLite */
    object Launch : AppScreen()
    /** Auth error with a message */
    data class AuthError(val message: String) : AppScreen()
    /** Settings screen */
    object Settings : AppScreen()
    /** Log viewer screen */
    object LogViewer : AppScreen()
}

/**
 * Represents the current state of the launch sequence.
 * Observed by LaunchScreen to show appropriate progress UI.
 */
sealed class LaunchState {
    /** Ready to launch — show Launch button */
    object Idle : LaunchState()
    /** Checking GitHub API for RuneLite updates */
    object CheckingUpdate : LaunchState()
    /** Downloading a newer RuneLite version */
    data class Updating(val fromVersion: String, val toVersion: String) : LaunchState()
    /** Running environment health check */
    object CheckingHealth : LaunchState()
    /** Refreshing OAuth2 tokens */
    object RefreshingTokens : LaunchState()
    /** Validating game session against Jagex API */
    object ValidatingSession : LaunchState()
    /** Launching RuneLite via Termux */
    object Launching : LaunchState()
    /** Launch failed with error */
    data class Failed(val message: String) : LaunchState()
}

/**
 * Result of the pre-launch health check.
 */
sealed class HealthCheckResult {
    /** All components healthy */
    object Healthy : HealthCheckResult()
    /** One or more components failed verification */
    data class Degraded(val failures: List<String>) : HealthCheckResult()
    /** Health check could not run (Termux not running, timeout, etc.) */
    object Inconclusive : HealthCheckResult()
}

class SetupViewModel(
    private val context: Context,
    private val orchestrator: SetupOrchestrator,
    private val commandRunner: TermuxCommandRunner,
    private val scriptManager: ScriptManager,
    private val credentialManager: CredentialManager,
    private val oAuth2Manager: JagexOAuth2Manager,
    private val displayPreferences: DisplayPreferences,
    private val presentationBackend: PresentationBackend,
    private val stateStore: SetupStateStore
) : ViewModel() {

    val steps: StateFlow<List<StepState>> = orchestrator.steps
    val currentOutput: StateFlow<String?> = orchestrator.currentOutput

    val canLaunch: StateFlow<Boolean> = orchestrator.steps
        .map { steps -> steps.all { it.status is StepStatus.Completed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Session state from the foreground service */
    val sessionState: StateFlow<SessionState> = RuneLiteSessionService.sessionState

    /** Session start time (millis) from the foreground service */
    val sessionStartTime: StateFlow<Long> = RuneLiteSessionService.sessionStartTime

    /** Current permission phase within the permissions step */
    val permissionPhase: StateFlow<PermissionPhase> = orchestrator.permissionPhase

    /** Whether the permissions step is actively waiting for user to complete phases */
    val isPermissionStepActive: StateFlow<Boolean> = combine(
        orchestrator.steps,
        orchestrator.awaitingPermissionCompletion
    ) { steps, awaiting ->
        val permStep = steps.find { it.step == SetupStep.EnablePermissions }
        permStep?.status is StepStatus.InProgress && awaiting
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Whether the command was already copied to clipboard (UI state) */
    private val _commandCopied = MutableStateFlow(false)
    val commandCopied: StateFlow<Boolean> = _commandCopied.asStateFlow()


    private val _currentScreen = MutableStateFlow<AppScreen>(AppScreen.Setup)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _launchState = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launchState: StateFlow<LaunchState> = _launchState.asStateFlow()
    private var activeLaunchJob: Job? = null

    private val _healthStatus = MutableStateFlow<HealthCheckResult?>(null)
    val healthStatus: StateFlow<HealthCheckResult?> = _healthStatus.asStateFlow()

    private val _showHealthDialog = MutableStateFlow<List<String>?>(null)
    val showHealthDialog: StateFlow<List<String>?> = _showHealthDialog.asStateFlow()

    private val setupStarted = AtomicBoolean(false)
    private val isRetrying = AtomicBoolean(false)
    private var x11ReadySwitchJob: Job? = null

    // Stored session ID between character list fetch and character selection
    @Volatile private var pendingSessionId: String? = null

    // Flag to auto-resume launch after re-auth (session expired -> GeckoView -> re-launch)
    private val pendingLaunchAfterAuth = AtomicBoolean(false)

    // Stored nonce for Step 2 consent id_token verification
    @Volatile private var pendingNonce: String? = null

    /**
     * Expose display name to the UI without leaking the full CredentialManager.
     */
    fun getDisplayName(): String? = credentialManager.getDisplayName()

    override fun onCleared() {
        super.onCleared()
        // Wipe sensitive auth state
        pendingSessionId = null
        pendingNonce = null
        pendingLaunchAfterAuth.set(false)
        AppLog.lifecycle("SetupViewModel.onCleared: auth state wiped")
    }

    fun startSetup() {
        val wasAlreadyStarted = !setupStarted.compareAndSet(false, true)
        AppLog.step("setup", "startSetup called: compareAndSet result=started wasAlreadyStarted=$wasAlreadyStarted")
        if (wasAlreadyStarted) return
        AppLog.step("setup", "startSetup: launching coroutine in viewModelScope")
        viewModelScope.launch {
            AppLog.perf("startSetup: coroutine started thread=${Thread.currentThread().name}")
            AppLog.step("setup", "startSetup coroutine: entry thread=${Thread.currentThread().name}")
            orchestrator.runSetup()
            AppLog.step("setup", "startSetup coroutine: runSetup complete — checking auth state")

            // After setup completes, decide what screen to show next
            val allDone = orchestrator.steps.value.all { it.status is StepStatus.Completed }
            if (allDone) {
                // Fix 8: credentialManager I/O must run off the Main thread
                val hasCreds = withContext(Dispatchers.IO) { credentialManager.hasCredentials() }
                if (hasCreds) {
                    AppLog.step("setup", "startSetup: setup complete + credentials present -> Launch")
                    _currentScreen.value = AppScreen.Launch
                } else {
                    AppLog.step("setup", "startSetup: setup complete + no credentials -> Login")
                    _currentScreen.value = AppScreen.Login
                }
            }
            AppLog.step("setup", "startSetup coroutine: exit")
            AppLog.perf("startSetup: coroutine completed")
        }
    }

    fun retry() {
        if (!isRetrying.compareAndSet(false, true)) return
        val failedStep = orchestrator.steps.value.firstOrNull { it.status is StepStatus.Failed }
        AppLog.step(failedStep?.step?.id ?: "unknown", "retry requested for step=${failedStep?.step?.id}")
        viewModelScope.launch {
            try {
                AppLog.perf("retry: coroutine started")
                orchestrator.retryCurrentStep()
                AppLog.perf("retry: coroutine completed")
            } finally {
                isRetrying.set(false)
            }
        }
    }

    /**
     * Internal launch method — fires the Termux command to run launch-runelite.sh.
     * Returns true if the command was successfully dispatched.
     */
    private suspend fun launch(envFilePath: String? = null): Boolean {
        AppLog.perf("launch: started")

        // Check Termux:X11 is installed before anything else
        val termuxHelper = TermuxPackageHelper(context)
        if (!presentationBackend.isInstalled(termuxHelper)) {
            AppLog.e("STEP", "launch: ${presentationBackend.displayName} not available — cannot launch")
            return false
        }

        presentationBackend.applyLaunchPreferences(context, displayPreferences)

        // Bring the presentation surface owner to the foreground via SetupActions.
        val presentationIntent = presentationBackend.createLaunchIntent(context)
        if (presentationIntent != null) {
            orchestrator.actions?.launchIntent(presentationIntent)
            AppLog.step("launch", "launch: sent launchIntent for ${presentationBackend.displayName}")
            // Let Termux:X11 come to the foreground before the hidden Termux
            // bootstrap begins. The actual shell launch no longer opens a
            // visible Termux terminal session.
            delay(700)
        } else {
            AppLog.w("STEP", "launch: could not get launch intent for ${presentationBackend.displayName}")
        }

        // Fire launch script with optional env file path as argument
        val scriptPath = scriptManager.getScriptPath("launch-runelite.sh")
        val arguments = if (envFilePath != null) arrayOf(envFilePath) else null
        AppLog.step("launch", "launch: attempting RuneLite launch scriptPath=$scriptPath envFile=${envFilePath ?: "none"}")
        val finalSuccess = commandRunner.launchBackground(
            commandPath = scriptPath,
            arguments = arguments
        )
        AppLog.step("launch", "launch: result success=$finalSuccess")
        if (!finalSuccess) {
            AppLog.e("STEP", "launch: failed to start RuneLite launch command")
        } else if (presentationIntent != null && presentationBackend.shouldWaitForReadySignal()) {
            waitForDisplayReadyAndSwitch(presentationIntent)
        }
        AppLog.perf("launch: completed success=$finalSuccess")
        return finalSuccess
    }

    private fun waitForDisplayReadyAndSwitch(x11LaunchIntent: Intent) {
        x11ReadySwitchJob?.cancel()
        x11ReadySwitchJob = viewModelScope.launch {
            repeat(20) { attempt ->
                val result = commandRunner.execute(
                    commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                    arguments = arrayOf(
                        "-c",
                        """
                        CURRENT_FILE="${'$'}PREFIX/tmp/rlt-session/current"
                        if [ -f "${'$'}CURRENT_FILE" ]; then
                            SESSION_DIR="$(cat "${'$'}CURRENT_FILE" 2>/dev/null)"
                            if [ -n "${'$'}SESSION_DIR" ] && [ -f "${'$'}SESSION_DIR/display.ready" ]; then
                                echo READY
                            fi
                        fi
                        """.trimIndent()
                    ),
                    background = true,
                    timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
                )
                if (result.stdout?.contains("READY") == true) {
                    orchestrator.actions?.launchIntent(x11LaunchIntent)
                    AppLog.step("launch", "launch: switched to Termux:X11 after ready signal")
                    return@launch
                }
                AppLog.step("launch", "launch: waiting for X11 ready signal attempt=${attempt + 1}")
                delay(500)
            }
            AppLog.w("STEP", "launch: timed out waiting for X11 ready signal")
        }
    }

    /**
     * Public launch entry point called from the UI.
     * Runs update check, health check, token refresh, then launches RuneLite.
     */
    fun launchRuneLite() {
        if (activeLaunchJob?.isActive == true) {
            AppLog.w("LAUNCH", "launchRuneLite: launch job already active, ignoring duplicate request")
            return
        }

        // Guard: don't launch if already running or starting
        val currentSession = RuneLiteSessionService.sessionState.value
        if (currentSession is SessionState.Running || currentSession is SessionState.Starting) {
            AppLog.w("LAUNCH", "launchRuneLite: session already ${currentSession::class.simpleName}, ignoring")
            return
        }
        activeLaunchJob = viewModelScope.launch {
            try {
                // 1. Update check (non-blocking on failure)
                _launchState.value = LaunchState.CheckingUpdate
                val updateOutput = runUpdateCheck()
                val updateLines = updateOutput.lines()
                val statusLine = updateLines.lastOrNull { it.startsWith("UPDATE_STATUS") } ?: ""
                when {
                    statusLine.contains("downloading") -> {
                        // Parse "UPDATE_STATUS downloading <old> -> <new>"
                        val parts = statusLine.removePrefix("UPDATE_STATUS downloading ").split(" -> ")
                        val fromVer = parts.getOrElse(0) { "unknown" }
                        val toVer = parts.getOrElse(1) { "unknown" }
                        _launchState.value = LaunchState.Updating(fromVer, toVer)
                        AppLog.step("update", "launchRuneLite: updating $fromVer -> $toVer")
                        // The script handles download internally — wait for the full output
                        // (it already ran to completion in runUpdateCheck)
                    }
                    statusLine.contains("updated") -> {
                        AppLog.step("update", "launchRuneLite: RuneLite updated successfully")
                    }
                    statusLine.contains("current") -> {
                        AppLog.step("update", "launchRuneLite: RuneLite already up to date")
                    }
                    statusLine.contains("offline") -> {
                        AppLog.w("UPDATE", "launchRuneLite: offline, launching with current version")
                    }
                    statusLine.contains("failed") -> {
                        AppLog.w("UPDATE", "launchRuneLite: update failed, launching with current version")
                    }
                    else -> {
                        AppLog.w("UPDATE", "launchRuneLite: unexpected update output, continuing")
                    }
                }

                // 2. Health check
                _launchState.value = LaunchState.CheckingHealth
                val healthResult = runHealthCheck()
                _healthStatus.value = healthResult
                if (healthResult is HealthCheckResult.Degraded) {
                    _launchState.value = LaunchState.Failed("Setup incomplete: ${healthResult.failures.joinToString()}")
                    _showHealthDialog.value = healthResult.failures
                    return@launch
                }

                // 3. Perform the actual launch (token refresh + env file + Termux)
                performLaunch()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("LAUNCH", "launchRuneLite: unexpected exception: ${e.message}", e)
                _launchState.value = LaunchState.Failed(e.message ?: "Unknown error")
            } finally {
                activeLaunchJob = null
            }
        }
    }

    /**
     * Internal method that handles token refresh, env file writing, and launch.
     * Can be called directly when bypassing health check ("Launch Anyway").
     */
    private suspend fun performLaunch() {
        // Token refresh
        // Fix 8: credentialManager I/O must run off the Main thread
        val hasCredentials = withContext(Dispatchers.IO) { credentialManager.hasCredentials() }
        if (hasCredentials) {
            _launchState.value = LaunchState.RefreshingTokens
            when (val result = refreshIfNeeded()) {
                is AuthResult.NeedsLogin -> {
                    AppLog.step("auth", "performLaunch: token expired, need re-login")
                    pendingLaunchAfterAuth.set(true)
                    _launchState.value = LaunchState.Idle
                    startLogin()
                    return
                }
                is AuthResult.NetworkError -> {
                    // Log but continue — existing credentials may still work
                    AppLog.w("AUTH", "performLaunch: refresh failed: ${result.exception.message}")
                }
                else -> {} // Valid or Refreshed — proceed
            }

            // Session validation: check if JX_SESSION_ID is still valid server-side.
            // Only applies to Jagex accounts that have a sessionId stored.
            // Legacy RuneScape accounts skip this (no sessionId stored).
            val creds = withContext(Dispatchers.IO) { credentialManager.getCredentials() }
            val sessionId = creds?.sessionId
            if (sessionId != null && sessionId.isNotEmpty()) {
                _launchState.value = LaunchState.ValidatingSession
                when (val validation = oAuth2Manager.validateSession(sessionId)) {
                    is SessionValidation.Valid -> {
                        AppLog.step("auth", "performLaunch: session valid, proceeding to launch")
                    }
                    is SessionValidation.Expired -> {
                        AppLog.step("auth", "performLaunch: session expired, triggering re-auth")
                        pendingLaunchAfterAuth.set(true)
                        withContext(Dispatchers.IO) { credentialManager.clearCredentials() }
                        val actions = orchestrator.actions
                        if (actions == null) {
                            pendingLaunchAfterAuth.set(false)
                            _launchState.value = LaunchState.Failed("Activity not available — please try again")
                            return
                        }
                        _launchState.value = LaunchState.Idle
                        startLogin()
                        return
                    }
                    is SessionValidation.NetworkError -> {
                        // Log but continue — match existing refreshIfNeeded NetworkError behavior
                        AppLog.w("AUTH", "performLaunch: session validation failed: ${validation.exception.message}")
                    }
                }
            }
        }

        _launchState.value = LaunchState.Launching

        // Deploy env file into Termux's home dir via RUN_COMMAND stdin.
        // The app's private filesDir is not accessible to Termux (different UID),
        // so we pipe the content through TermuxCommandRunner the same way scripts are deployed.
        var envFilePath: String? = null
        // Fix 8: credentialManager I/O must run off the Main thread
        val creds = withContext(Dispatchers.IO) { credentialManager.getCredentials() }
        if (creds != null) {
            try {
                val termuxEnvPath = "${TermuxCommandRunner.TERMUX_HOME_PATH}/.rlt-launch-env.sh"
                val content = buildString {
                    appendLine("export JX_SESSION_ID=\"${shellEscape(creds.sessionId)}\"")
                    appendLine("export JX_CHARACTER_ID=\"${shellEscape(creds.characterId)}\"")
                    appendLine("export JX_DISPLAY_NAME=\"${shellEscape(creds.displayName)}\"")
                }
                val deployCommand = "cat > $termuxEnvPath && chmod 600 $termuxEnvPath"
                val result = commandRunner.execute(
                    commandPath = "${TermuxCommandRunner.TERMUX_BIN_PATH}/bash",
                    arguments = arrayOf("-c", deployCommand),
                    stdin = content,
                    background = true,
                    timeoutMs = TermuxCommandRunner.TIMEOUT_VERIFY_MS
                )
                if (result.isSuccess) {
                    envFilePath = termuxEnvPath
                    AppLog.step("auth", "performLaunch: env file deployed to Termux at $termuxEnvPath (credentials masked)")
                } else {
                    AppLog.e("AUTH", "performLaunch: failed to deploy env file: exitCode=${result.exitCode} error=${result.error}")
                }
            } catch (e: Exception) {
                AppLog.e("AUTH", "performLaunch: failed to deploy env file: ${e.message}", e)
            }
        }

        val launchSuccess = launch(envFilePath)
        if (launchSuccess) {
            _launchState.value = LaunchState.Idle
            startSessionService()
        } else {
            _launchState.value = LaunchState.Failed("Failed to start RuneLite launch command")
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle (foreground service)
    // -------------------------------------------------------------------------

    /**
     * Start the session foreground service. Called after successful launch.
     * Soft-prompts for notification permission first (non-blocking).
     */
    private fun startSessionService() {
        // Soft-prompt for notification permission (non-blocking)
        if (!orchestrator.hasNotificationPermission()) {
            AppLog.lifecycle("SetupViewModel: requesting notification permission (soft prompt)")
            orchestrator.actions?.requestNotificationPermission()
        }
        val intent = Intent(context, RuneLiteSessionService::class.java).apply {
            action = RuneLiteSessionService.ACTION_START_SESSION
        }
        context.startForegroundService(intent)
        AppLog.lifecycle("SetupViewModel: startSessionService dispatched")
    }

    /**
     * Stop the RuneLite session via the foreground service.
     */
    fun stopSession() {
        val intent = Intent(context, RuneLiteSessionService::class.java).apply {
            action = RuneLiteSessionService.ACTION_STOP_SESSION
        }
        context.startService(intent)
        AppLog.lifecycle("SetupViewModel: stopSession dispatched")
    }

    /**
     * Switch to the game display (Termux:X11) via the foreground service.
     */
    fun switchToGame() {
        val intent = Intent(context, RuneLiteSessionService::class.java).apply {
            action = RuneLiteSessionService.ACTION_SWITCH_TO_GAME
        }
        context.startService(intent)
        AppLog.lifecycle("SetupViewModel: switchToGame dispatched")
    }

    /**
     * Check if a session is still running. Called on app resume.
     */
    fun checkSession() {
        val currentState = RuneLiteSessionService.sessionState.value
        if (currentState is SessionState.Running || currentState is SessionState.Starting) {
            val intent = Intent(context, RuneLiteSessionService::class.java).apply {
                action = RuneLiteSessionService.ACTION_CHECK_SESSION
            }
            context.startService(intent)
            AppLog.lifecycle("SetupViewModel: checkSession dispatched (current=$currentState)")
        }
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    fun navigateToSettings() {
        _currentScreen.value = AppScreen.Settings
    }

    fun navigateToLogViewer() {
        _currentScreen.value = AppScreen.LogViewer
    }

    fun navigateBackToLaunch() {
        _currentScreen.value = AppScreen.Launch
    }

    fun signOut() {
        credentialManager.clearCredentials()
        _currentScreen.value = AppScreen.Login
    }

    fun resetSetup() {
        viewModelScope.launch {
            stateStore.clearAll()
            scriptManager.invalidateDeployCache()
            orchestrator.resetState()
            setupStarted.set(false)
            _currentScreen.value = AppScreen.Setup
        }
    }

    fun dismissHealthDialog() {
        _showHealthDialog.value = null
    }

    fun launchAnyway() {
        _showHealthDialog.value = null
        viewModelScope.launch {
            performLaunch()
        }
    }

    fun runSetupForHealth() {
        _showHealthDialog.value = null
        // Reset setup state so startSetup() re-runs via LaunchedEffect
        setupStarted.set(false)
        _currentScreen.value = AppScreen.Setup
    }

    // -------------------------------------------------------------------------
    // Update check
    // -------------------------------------------------------------------------

    /**
     * Run update-runelite.sh and parse the output.
     * Returns the raw stdout or empty string on failure.
     */
    private suspend fun runUpdateCheck(): String {
        return try {
            val deployed = scriptManager.deployScripts()
            if (!deployed) {
                AppLog.w("UPDATE", "runUpdateCheck: script deployment failed")
                return ""
            }
            val result = commandRunner.execute(
                commandPath = scriptManager.getScriptPath("update-runelite.sh"),
                background = true,
                timeoutMs = 60L * 1000
            )
            val output = result.stdout ?: ""
            AppLog.step("update", "runUpdateCheck: exitCode=${result.exitCode} output=$output")
            output
        } catch (e: TimeoutCancellationException) {
            AppLog.w("UPDATE", "runUpdateCheck: timed out after 60s")
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("UPDATE", "runUpdateCheck: exception: ${e.message}")
            ""
        }
    }

    // -------------------------------------------------------------------------
    // Health check
    // -------------------------------------------------------------------------

    /**
     * Run health-check.sh and parse the output into a HealthCheckResult.
     */
    private suspend fun runHealthCheck(): HealthCheckResult {
        return try {
            val deployed = scriptManager.deployScripts()
            if (!deployed) {
                AppLog.w("HEALTH", "runHealthCheck: script deployment failed")
                return HealthCheckResult.Inconclusive
            }
            val result = commandRunner.execute(
                commandPath = scriptManager.getScriptPath("health-check.sh"),
                background = true,
                timeoutMs = 10L * 1000
            )
            val output = result.stdout ?: ""
            AppLog.step("health", "runHealthCheck: exitCode=${result.exitCode} output=$output")

            val failures = output.lines()
                .filter { it.startsWith("HEALTH") && it.contains("FAIL") }
                .map { line ->
                    val parts = line.split(" ")
                    val component = parts.getOrElse(1) { "unknown" }
                    val reason = parts.getOrElse(3) { "unknown" }
                    "$component: $reason"
                }

            if (failures.isEmpty()) HealthCheckResult.Healthy
            else HealthCheckResult.Degraded(failures)
        } catch (e: TimeoutCancellationException) {
            AppLog.w("HEALTH", "runHealthCheck: timed out after 10s")
            HealthCheckResult.Inconclusive
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("HEALTH", "runHealthCheck: exception: ${e.message}")
            HealthCheckResult.Inconclusive
        }
    }

    // -------------------------------------------------------------------------
    // Auth: GeckoView-based Login Flow
    //
    // GeckoAuthActivity handles Step 1 + Step 2 internally.
    // Returns tokens via ActivityResult.
    // ViewModel handles Step 3 (game session API calls).
    // -------------------------------------------------------------------------

    /**
     * Start the Jagex OAuth2 login flow via GeckoAuthActivity.
     *
     * Generates PKCE codes, state nonces, and nonce for Step 2.
     * Launches GeckoAuthActivity which handles:
     *   - Step 1: Account auth (Cloudflare, login, jagex: capture, token exchange)
     *   - Step 2: Consent (localhost redirect interception via GeckoView)
     *
     * Results return via ActivityResult -> handleAuthResult().
     */
    fun startLogin() {
        val actions = orchestrator.actions
        if (actions == null) {
            AppLog.e("AUTH", "startLogin: no actions bound — activity not available")
            _currentScreen.value = AppScreen.AuthError("Activity not available")
            return
        }

        val codeVerifier = PkceHelper.generateVerifier()
        val step1State = PkceHelper.generateState()
        val step2State = PkceHelper.generateState()
        val nonce = oAuth2Manager.generateNonce()
        pendingNonce = nonce

        val step1Url = oAuth2Manager.buildStep1AuthUrl(codeVerifier, step1State).toString()
        val step2Url = oAuth2Manager.buildStep2ConsentUrl(step2State, nonce).toString()

        val intent = GeckoAuthActivity.createIntent(
            context, step1Url, step2Url, codeVerifier,
            step1State, step2State, nonce
        )

        AppLog.step("auth", "startLogin: launching GeckoAuthActivity")
        actions.launchAuthActivity(intent)
    }

    /**
     * Handle the result from GeckoAuthActivity.
     * Called by MainActivity when the ActivityResultLauncher delivers a result.
     */
    fun handleAuthResult(result: ActivityResult) {
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val data = result.data ?: run {
                    AppLog.e("AUTH", "handleAuthResult: RESULT_OK but no data")
                    _currentScreen.value = AppScreen.AuthError("Authentication failed: no data received")
                    return
                }

                val loginProvider = data.getStringExtra(GeckoAuthActivity.RESULT_LOGIN_PROVIDER) ?: "jagex"
                val accessToken = data.getStringExtra(GeckoAuthActivity.RESULT_ACCESS_TOKEN)
                val refreshToken = data.getStringExtra(GeckoAuthActivity.RESULT_REFRESH_TOKEN)
                val idToken = data.getStringExtra(GeckoAuthActivity.RESULT_ID_TOKEN)
                val expiresIn = data.getLongExtra(GeckoAuthActivity.RESULT_EXPIRES_IN, 0L)
                val accessTokenExpiry = data.getLongExtra(GeckoAuthActivity.RESULT_ACCESS_TOKEN_EXPIRY, 0L)

                AppLog.step("auth", "handleAuthResult: OK loginProvider=$loginProvider hasIdToken=${idToken != null}")

                viewModelScope.launch {
                    try {
                        when (loginProvider) {
                            "runescape" -> {
                                // Legacy RuneScape account — store Step 1 tokens directly
                                AppLog.step("auth", "handleAuthResult: RuneScape account — storing tokens, going to Launch")
                                if (accessToken != null) {
                                    val tokenResponse = TokenResponse(
                                        accessToken = accessToken,
                                        refreshToken = refreshToken,
                                        idToken = idToken,
                                        expiresIn = expiresIn,
                                        accessTokenExpiry = accessTokenExpiry
                                    )
                                    withContext(Dispatchers.IO) { credentialManager.storeTokens(tokenResponse) }
                                }
                                navigateToLaunchOrResume()
                            }
                            else -> {
                                // Jagex account — store Step 1 tokens, then use Step 2 consent id_token
                                AppLog.step("auth", "handleAuthResult: Jagex account — proceeding to game session")
                                if (idToken == null) {
                                    _currentScreen.value = AppScreen.AuthError("No consent token received")
                                    return@launch
                                }
                                // Store Step 1 OAuth tokens (access_token for game auth, refresh_token for renewal)
                                if (accessToken != null) {
                                    val tokenResponse = TokenResponse(
                                        accessToken = accessToken,
                                        refreshToken = refreshToken,
                                        idToken = idToken,
                                        expiresIn = expiresIn,
                                        accessTokenExpiry = accessTokenExpiry
                                    )
                                    withContext(Dispatchers.IO) { credentialManager.storeTokens(tokenResponse) }
                                }
                                runStep3GameSession(idToken)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OAuthException) {
                        AppLog.e("AUTH", "handleAuthResult: OAuth error: ${e.message}", e)
                        pendingLaunchAfterAuth.set(false)
                        _currentScreen.value = AppScreen.AuthError(e.message ?: "Authentication failed")
                    } catch (e: Exception) {
                        AppLog.e("AUTH", "handleAuthResult: exception: ${e.message}", e)
                        pendingLaunchAfterAuth.set(false)
                        _currentScreen.value = AppScreen.AuthError(e.message ?: "Login failed")
                    } finally {
                        pendingNonce = null
                    }
                }
            }
            Activity.RESULT_CANCELED -> {
                AppLog.step("auth", "handleAuthResult: login cancelled by user")
                pendingLaunchAfterAuth.set(false)
                _currentScreen.value = AppScreen.AuthError("Login cancelled — tap Sign In to try again")
                pendingNonce = null
            }
            else -> {
                // RESULT_FIRST_USER = error case
                val error = result.data?.getStringExtra(GeckoAuthActivity.RESULT_ERROR) ?: "Login failed"
                AppLog.e("AUTH", "handleAuthResult: error: $error")
                pendingLaunchAfterAuth.set(false)
                _currentScreen.value = AppScreen.AuthError(error)
                pendingNonce = null
            }
        }
    }

    /**
     * Step 3: Game session API calls.
     * POST id_token to /sessions -> sessionId
     * GET /accounts with Bearer sessionId -> character list
     */
    private suspend fun runStep3GameSession(idToken: String) {
        AppLog.step("auth", "startLogin: Step 3 — creating game session")

        val sessionId = oAuth2Manager.createGameSession(idToken)
        AppLog.step("auth", "startLogin: Step 3 — session created, fetching accounts")

        val characters = oAuth2Manager.fetchAccounts(sessionId)
        AppLog.step("auth", "startLogin: Step 3 — ${characters.size} character(s) found")

        if (characters.isEmpty()) {
            AppLog.e("AUTH", "runStep3GameSession: no characters found on account")
            _currentScreen.value = AppScreen.AuthError("No characters found on this account")
            return
        }

        if (characters.size == 1) {
            AppLog.step("auth", "runStep3GameSession: single character, auto-selecting")
            val character = characters[0]
            // Fix 8: credentialManager I/O must run off the Main thread
            withContext(Dispatchers.IO) {
                credentialManager.storeGameSession(sessionId, character.accountId, character.displayName)
            }
            navigateToLaunchOrResume()
        } else {
            AppLog.step("auth", "runStep3GameSession: ${characters.size} characters, showing selection screen")
            pendingSessionId = sessionId
            _currentScreen.value = AppScreen.CharacterSelect(characters)
        }
    }

    /**
     * Called when the user selects a character from the character selection screen.
     */
    fun onCharacterSelected(character: GameCharacter) {
        viewModelScope.launch {
            try {
                val sessionId = pendingSessionId ?: run {
                    AppLog.e("AUTH", "onCharacterSelected: no pending session ID")
                    _currentScreen.value = AppScreen.AuthError("Session expired — please log in again")
                    return@launch
                }
                // Fix 8: credentialManager I/O must run off the Main thread
                withContext(Dispatchers.IO) {
                    credentialManager.storeGameSession(sessionId, character.accountId, character.displayName)
                }
                pendingSessionId = null
                AppLog.step("auth", "onCharacterSelected: session stored -> Launch")
                navigateToLaunchOrResume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("AUTH", "onCharacterSelected: exception: ${e.message}", e)
                _currentScreen.value = AppScreen.AuthError(e.message ?: "Session creation failed")
            }
        }
    }

    /**
     * Skip login — proceed to launch without credentials.
     */
    fun skipLogin() {
        AppLog.step("auth", "skipLogin: skipping login, going to Launch")
        _currentScreen.value = AppScreen.Launch
    }

    /**
     * Navigate to Launch screen, or auto-resume performLaunch() if a pending
     * re-auth was triggered by session validation.
     *
     * Called after successful auth completion (Step 3 game session stored, or
     * RuneScape account tokens stored). Must be called from a coroutine context.
     */
    private suspend fun navigateToLaunchOrResume() {
        if (pendingLaunchAfterAuth.compareAndSet(true, false)) {
            AppLog.step("auth", "navigateToLaunchOrResume: auto-resuming launch after re-auth")
            performLaunch()
        } else {
            _currentScreen.value = AppScreen.Launch
        }
    }

    // -------------------------------------------------------------------------
    // Auth: Token refresh
    // -------------------------------------------------------------------------

    /**
     * Check if the access token needs refreshing; refresh if so.
     * Called before launch to ensure credentials are fresh.
     */
    private suspend fun refreshIfNeeded(): AuthResult {
        // Fix 8: credentialManager I/O must run off the Main thread
        val expiry = withContext(Dispatchers.IO) { credentialManager.getAccessTokenExpiry() }
        val now = System.currentTimeMillis() / 1000L
        if (now < expiry - 60) {
            AppLog.step("auth", "refreshIfNeeded: token still valid, expires in ${expiry - now}s")
            return AuthResult.Valid
        }

        val refreshToken = withContext(Dispatchers.IO) { credentialManager.getRefreshToken() }
        if (refreshToken == null) {
            AppLog.step("auth", "refreshIfNeeded: no refresh token -> NeedsLogin")
            return AuthResult.NeedsLogin
        }

        return try {
            AppLog.step("auth", "refreshIfNeeded: token expired, refreshing")
            val response = oAuth2Manager.refreshTokens(refreshToken)
            withContext(Dispatchers.IO) { credentialManager.storeTokens(response) }
            AppLog.step("auth", "refreshIfNeeded: refresh succeeded")
            AuthResult.Refreshed
        } catch (e: OAuthException) {
            if (e.httpCode == 401 || e.httpCode == 400) {
                // 401 = token_inactive, 400 = invalid_grant — both mean refresh token is dead
                AppLog.w("AUTH", "refreshIfNeeded: refresh token rejected (${e.httpCode}) -> NeedsLogin")
                AuthResult.NeedsLogin
            } else {
                AppLog.w("AUTH", "refreshIfNeeded: HTTP error ${e.httpCode} -> NetworkError")
                AuthResult.NetworkError(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("AUTH", "refreshIfNeeded: exception -> NetworkError: ${e.message}")
            AuthResult.NetworkError(e)
        }
    }

    // -------------------------------------------------------------------------
    // Phased permissions
    // -------------------------------------------------------------------------

    /**
     * Combined command that configures both allow-external-apps and extra keys in one paste.
     * No single quotes — avoids Android clipboard curly-quote corruption.
     * Double quotes are safe — Android clipboard doesn't corrupt them.
     */
    val termuxConfigCommand: String =
        "mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && " +
            "echo \"extra-keys = [['ESC','CTRL','ALT','LEFT','DOWN','UP','RIGHT','TAB']," +
            "['~','/','-','|','HOME','END','PGUP','PGDN']]\" >> ~/.termux/termux.properties && " +
            "termux-reload-settings && echo Done && am start -n ${context.packageName}/.MainActivity"

    /**
     * Copy the combined config command to the Android clipboard and launch Termux.
     * The user long-presses to paste it in Termux, then presses Enter.
     */
    fun copyConfigAndOpenTermux() {
        AppLog.ui("copyConfigAndOpenTermux: copying config command to clipboard and launching Termux")

        // Copy to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Termux Config", termuxConfigCommand)
        clipboard.setPrimaryClip(clip)
        _commandCopied.value = true

        // Launch Termux
        val intent = context.packageManager.getLaunchIntentForPackage(SetupOrchestrator.TERMUX_PACKAGE)
        if (intent != null) {
            orchestrator.actions?.launchIntent(intent)
        } else {
            AppLog.w("PERM", "copyConfigAndOpenTermux: could not get launch intent for Termux")
        }

    }

    /**
     * Called when the user returns from Termux (onResume) or taps "Retry" on a permission phase.
     * Tries to advance the current permission phase.
     */
    fun checkPermissionPhase() {
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (!orchestrator.awaitingPermissionCompletion.value) return@launch
                val advanced = orchestrator.advancePermissionPhase()
                AppLog.step("permissions", "checkPermissionPhase: advanced=$advanced phase=${orchestrator.permissionPhase.value::class.simpleName}")
            } finally {
                isRetrying.set(false)
            }
        }
    }

    /**
     * Request the next required battery optimization exemption.
     * Called from the UI when the battery optimization phase is active.
     */
    fun requestBatteryOptimization() {
        AppLog.ui("requestBatteryOptimization: requesting next required exemption")
        orchestrator.requestBatteryOptimization()
        // Also request for our own app
        orchestrator.requestOwnBatteryOptimization()
    }

    fun openDirectSurfaceProbe() {
        val intent = PresentationBackends.directSurfaceProbe.createLaunchIntent(context)
        if (intent != null) {
            AppLog.ui("openDirectSurfaceProbe: launching experimental probe activity")
            orchestrator.actions?.launchIntent(intent)
        } else {
            AppLog.w("SURFACE", "openDirectSurfaceProbe: no launch intent available")
        }
    }

    fun recheckPermissions() {
        // Re-evaluate completed steps on return from settings/install screens.
        // Guard with isRetrying to prevent concurrent retryCurrentStep() calls if the user
        // navigates back rapidly (e.g., quick back-press from install confirmation screen).
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                // If we're in the phased permissions flow, try to advance
                if (orchestrator.awaitingPermissionCompletion.value) {
                    orchestrator.advancePermissionPhase()
                    return@launch
                }

                val currentSteps = orchestrator.steps.value
                val hasFailedInstall = currentSteps.any {
                    (it.step == SetupStep.InstallTermux || it.step == SetupStep.InstallTermuxX11) &&
                        it.status is StepStatus.Failed
                }
                if (hasFailedInstall) {
                    orchestrator.retryCurrentStep()
                }
            } finally {
                isRetrying.set(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Activity binding
    // -------------------------------------------------------------------------

    fun bindActions(activity: Activity, authLauncher: ActivityResultLauncher<Intent>) {
        orchestrator.actions = object : SetupActions {
            override fun requestInstallPermission() {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }

            override fun requestTermuxPermission() {
                val permission = "com.termux.permission.RUN_COMMAND"
                if (activity.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(permission), REQUEST_TERMUX_PERMISSION)
                }
            }

            override fun requestNotificationPermission() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_NOTIFICATION_PERMISSION
                        )
                    }
                }
            }

            override fun requestBatteryOptimization(packageName: String) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                activity.startActivity(intent)
            }

            override fun openAppSettings(packageName: String) {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                activity.startActivity(intent)
            }

            override fun launchIntent(intent: Intent) {
                activity.startActivity(intent)
            }

            override fun launchAuthActivity(intent: Intent) {
                authLauncher.launch(intent)
            }
        }
    }

    fun unbindActions() {
        orchestrator.actions = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Escape a string for safe use inside double-quoted shell strings.
     * Escapes all shell metacharacters: \, ", $, `, !
     * Safe against injection when the env file is `source`d by bash.
     */
    private fun shellEscape(value: String): String = value
        .replace("\u0000", "")  // Strip null bytes
        .replace("\n", "")      // Strip newlines (prevent line injection)
        .replace("\r", "")      // Strip carriage returns
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\$", "\\\$")
        .replace("`", "\\`")
        .replace("!", "\\!")


    companion object {
        const val REQUEST_TERMUX_PERMISSION = 1001
        const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }

    class Factory(
        activity: Activity,
        private val httpClient: OkHttpClient
    ) : ViewModelProvider.Factory {
        // Store applicationContext only — storing Activity would leak it across config changes
        // since ViewModelProvider retains the Factory for the lifetime of the ViewModelStore.
        private val appContext: Context = activity.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val context = appContext
            val termuxHelper = TermuxPackageHelper(context)
            val apkDownloader = ApkDownloader(context, httpClient)
            val apkInstaller = ApkInstaller(context)
            val commandRunner = TermuxCommandRunner(context)
            val scriptManager = ScriptManager(context, commandRunner)
            val cleanupManager = CleanupManager(context)
            val stateStore = SetupStateStore(context)
            val credentialManager = CredentialManager(context)
            val oAuth2Manager = JagexOAuth2Manager(httpClient)
            val displayPreferences = DisplayPreferences(context)
            val presentationBackend = PresentationBackends.stable
            val orchestrator = SetupOrchestrator(
                context, termuxHelper, apkDownloader, apkInstaller,
                commandRunner, scriptManager, cleanupManager, stateStore
            )
            return SetupViewModel(
                context, orchestrator, commandRunner, scriptManager,
                credentialManager, oAuth2Manager, displayPreferences,
                presentationBackend, stateStore
            ) as T
        }
    }
}

package com.runelitetablet.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runelitetablet.auth.AuthCodeResult
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.auth.CredentialManager
import com.runelitetablet.auth.GameCharacter
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.auth.LocalhostAuthServer
import com.runelitetablet.auth.OAuthException
import com.runelitetablet.auth.PkceHelper
import com.runelitetablet.cleanup.CleanupManager
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import com.runelitetablet.ui.DisplayPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
    private val stateStore: SetupStateStore
) : ViewModel() {

    val steps: StateFlow<List<StepState>> = orchestrator.steps
    val currentOutput: StateFlow<String?> = orchestrator.currentOutput

    val canLaunch: StateFlow<Boolean> = orchestrator.steps
        .map { steps -> steps.all { it.status is StepStatus.Completed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    private val _healthStatus = MutableStateFlow<HealthCheckResult?>(null)
    val healthStatus: StateFlow<HealthCheckResult?> = _healthStatus.asStateFlow()

    private val _showHealthDialog = MutableStateFlow<List<String>?>(null)
    val showHealthDialog: StateFlow<List<String>?> = _showHealthDialog.asStateFlow()

    private val setupStarted = AtomicBoolean(false)
    private val isRetrying = AtomicBoolean(false)

    // Auth state — lives only for the duration of one login attempt
    @Volatile private var codeVerifier: String? = null
    @Volatile private var authState: String? = null

    // Stored access token between character list fetch and character selection
    @Volatile private var pendingAccessToken: String? = null

    // Active localhost server for Custom Tab redirect capture.
    // Tracked so onResume() can detect Custom Tab dismissal without a redirect.
    @Volatile private var activeAuthServer: LocalhostAuthServer? = null
    @Volatile private var activeLoginJob: Job? = null

    /**
     * Expose display name to the UI without leaking the full CredentialManager.
     */
    fun getDisplayName(): String? = credentialManager.getDisplayName()

    override fun onCleared() {
        super.onCleared()
        // Stop any active auth server and wipe sensitive state
        activeAuthServer?.stop()
        activeAuthServer = null
        activeLoginJob?.cancel()
        activeLoginJob = null
        pendingAccessToken = null
        codeVerifier = null
        authState = null
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
                if (credentialManager.hasCredentials()) {
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
    private fun launch(envFilePath: String? = null): Boolean {
        AppLog.perf("launch: started")

        // Check Termux:X11 is installed before anything else
        val termuxHelper = TermuxPackageHelper(context)
        if (!termuxHelper.isTermuxX11Installed()) {
            AppLog.e("STEP", "launch: Termux:X11 not installed — cannot launch")
            return false
        }

        // Set Termux:X11 preferences before launch using display settings
        val prefIntent = Intent("com.termux.x11.CHANGE_PREFERENCE").apply {
            setPackage("com.termux.x11")
            putExtra("fullscreen", displayPreferences.fullscreen.toString())
            putExtra("showAdditionalKbd", displayPreferences.showKeyboardBar.toString())
            putExtra("displayResolutionMode", displayPreferences.resolutionMode)
            if (displayPreferences.resolutionMode == "exact") {
                putExtra("displayResolutionExactX", displayPreferences.customWidth.toString())
                putExtra("displayResolutionExactY", displayPreferences.customHeight.toString())
            }
        }
        context.sendBroadcast(prefIntent)
        AppLog.step("launch", "launch: sent CHANGE_PREFERENCE broadcast to Termux:X11 (mode=${displayPreferences.resolutionMode} fullscreen=${displayPreferences.fullscreen})")

        // Bring Termux:X11 to foreground via SetupActions (routes through Activity)
        val x11LaunchIntent = context.packageManager.getLaunchIntentForPackage(
            TermuxPackageHelper.TERMUX_X11_PACKAGE
        )
        if (x11LaunchIntent != null) {
            orchestrator.actions?.launchIntent(x11LaunchIntent)
            AppLog.step("launch", "launch: sent launchIntent for Termux:X11")
        } else {
            AppLog.w("STEP", "launch: could not get launch intent for Termux:X11")
        }

        // Fire launch script with optional env file path as argument
        val scriptPath = scriptManager.getScriptPath("launch-runelite.sh")
        val arguments = if (envFilePath != null) arrayOf(envFilePath) else null
        AppLog.step("launch", "launch: attempting RuneLite launch scriptPath=$scriptPath envFile=${envFilePath ?: "none"}")
        val success = commandRunner.launch(
            commandPath = scriptPath,
            arguments = arguments,
            sessionAction = TermuxCommandRunner.SESSION_ACTION_SWITCH_NEW
        )
        AppLog.step("launch", "launch: result success=$success")
        if (!success) {
            AppLog.e("STEP", "launch: failed to start RuneLite launch command")
        }
        AppLog.perf("launch: completed success=$success")
        return success
    }

    /**
     * Public launch entry point called from the UI.
     * Runs update check, health check, token refresh, then launches RuneLite.
     */
    fun launchRuneLite() {
        viewModelScope.launch {
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
            }
        }
    }

    /**
     * Internal method that handles token refresh, env file writing, and launch.
     * Can be called directly when bypassing health check ("Launch Anyway").
     */
    private suspend fun performLaunch() {
        // Token refresh
        if (credentialManager.hasCredentials()) {
            _launchState.value = LaunchState.RefreshingTokens
            when (val result = refreshIfNeeded()) {
                is AuthResult.NeedsLogin -> {
                    AppLog.step("auth", "performLaunch: token expired, need re-login")
                    _launchState.value = LaunchState.Idle
                    _currentScreen.value = AppScreen.Login
                    return
                }
                is AuthResult.NetworkError -> {
                    // Log but continue — existing credentials may still work
                    AppLog.w("AUTH", "performLaunch: refresh failed: ${result.exception.message}")
                }
                else -> {} // Valid or Refreshed — proceed
            }
        }

        _launchState.value = LaunchState.Launching

        // Write env file if we have credentials
        var envFilePath: String? = null
        var envFile: java.io.File? = null
        val creds = credentialManager.getCredentials()
        if (creds != null) {
            try {
                val file = java.io.File(
                    context.filesDir,
                    "launch_env_${System.currentTimeMillis()}.sh"
                )
                withContext(Dispatchers.IO) {
                    file.writeText(buildString {
                        appendLine("export JX_SESSION_ID='${shellEscape(creds.sessionId)}'")
                        appendLine("export JX_CHARACTER_ID='${shellEscape(creds.characterId)}'")
                        appendLine("export JX_DISPLAY_NAME='${shellEscape(creds.displayName)}'")
                        creds.accessToken?.let { appendLine("export JX_ACCESS_TOKEN='${shellEscape(it)}'") }
                    })
                }
                envFile = file
                envFilePath = file.absolutePath
                AppLog.step("auth", "performLaunch: env file written to $envFilePath (credentials masked)")
            } catch (e: Exception) {
                AppLog.e("AUTH", "performLaunch: failed to write env file: ${e.message}", e)
            }
        }

        try {
            val launchSuccess = launch(envFilePath)
            if (launchSuccess) {
                _launchState.value = LaunchState.Idle
            } else {
                _launchState.value = LaunchState.Failed("Failed to start RuneLite launch command")
            }
        } finally {
            // Clean up env file after a short delay to ensure Termux has time to read it
            val capturedEnvFile = envFile
            if (capturedEnvFile != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(5000)
                    capturedEnvFile.delete()
                    AppLog.step("auth", "performLaunch: env file deleted")
                }
            }
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
        // Navigate to setup screen — the failures indicate which steps to re-run
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
            val result = withTimeout(60_000L) {
                commandRunner.execute(
                    commandPath = scriptManager.getScriptPath("update-runelite.sh"),
                    background = true,
                    timeoutMs = 60L * 1000
                )
            }
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
            val result = withTimeout(10_000L) {
                commandRunner.execute(
                    commandPath = scriptManager.getScriptPath("health-check.sh"),
                    background = true,
                    timeoutMs = 10L * 1000
                )
            }
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
    // Auth: Login flow
    // -------------------------------------------------------------------------

    /**
     * Start the Jagex OAuth2 login flow using Chrome Custom Tab + localhost redirect.
     *
     * Flow:
     * 1. Start LocalhostAuthServer on a random port
     * 2. Build auth URL with localhost redirect
     * 3. Open Chrome Custom Tab to the auth URL
     * 4. Await redirect on localhost (captures auth code)
     * 5. Exchange code for tokens
     * 6. Fetch characters / start second auth step if needed
     */
    fun startLogin() {
        // Cancel any previous login attempt
        activeLoginJob?.cancel()
        activeAuthServer?.stop()

        val server = LocalhostAuthServer()
        activeLoginJob = viewModelScope.launch {
            activeAuthServer = server
            try {
                val port = server.start()
                codeVerifier = PkceHelper.generateVerifier()
                authState = PkceHelper.generateState()

                val verifier = codeVerifier ?: run {
                    AppLog.e("AUTH", "startLogin: codeVerifier is null unexpectedly")
                    _currentScreen.value = AppScreen.AuthError("Authentication state lost")
                    return@launch
                }
                val state = authState ?: run {
                    AppLog.e("AUTH", "startLogin: authState is null unexpectedly")
                    _currentScreen.value = AppScreen.AuthError("Authentication state lost")
                    return@launch
                }

                val redirectUri = "http://localhost:$port"
                val authUrl = oAuth2Manager.buildAuthUrl(verifier, state, port)
                AppLog.step("auth", "startLogin: launching Custom Tab with localhost redirect on port $port")

                // Open Chrome Custom Tab
                val actions = orchestrator.actions
                if (actions == null) {
                    AppLog.e("AUTH", "startLogin: no actions bound — activity not available")
                    _currentScreen.value = AppScreen.AuthError("Activity not available")
                    return@launch
                }

                val customTabIntent = buildCustomTabIntent()
                customTabIntent.intent.data = authUrl
                actions.launchIntent(customTabIntent.intent)

                // Wait for localhost redirect
                val result = server.awaitRedirect(120_000L)
                activeAuthServer = null

                when (result) {
                    is AuthCodeResult.Success -> handleAuthCode(result.code, result.state, redirectUri)
                    is AuthCodeResult.Error -> {
                        AppLog.e("AUTH", "startLogin: OAuth error: ${result.error} — ${result.description}")
                        _currentScreen.value = AppScreen.AuthError("Login failed: ${result.description}")
                    }
                    is AuthCodeResult.Cancelled -> {
                        AppLog.step("auth", "startLogin: timed out waiting for redirect")
                        _currentScreen.value = AppScreen.AuthError("Login timed out — try again")
                    }
                }
            } catch (e: CancellationException) {
                AppLog.step("auth", "startLogin: cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.e("AUTH", "startLogin: exception: ${e.message}", e)
                _currentScreen.value = AppScreen.AuthError(e.message ?: "Login failed")
            } finally {
                server.stop()
                // Only clear tracking refs if this is still the active login
                // (prevents a cancelled job from clearing a newer job's refs)
                if (activeAuthServer === server) activeAuthServer = null
            }
        }
    }

    /**
     * Called from onResume() to detect Custom Tab dismissal.
     * If a login is in progress and the user returns without completing auth,
     * cancel the login after a short grace period.
     */
    fun checkLoginDismissal() {
        val server = activeAuthServer ?: return
        val job = activeLoginJob ?: return
        // Give a short grace period — the redirect may arrive slightly after onResume
        viewModelScope.launch {
            delay(500)
            // If the server is still active (no redirect captured), user dismissed the Custom Tab
            if (activeAuthServer === server && job.isActive) {
                AppLog.step("auth", "checkLoginDismissal: Custom Tab dismissed without redirect — cancelling login")
                job.cancel()
                activeAuthServer = null
                activeLoginJob = null
                server.stop()
                _currentScreen.value = AppScreen.Login
            }
        }
    }

    private suspend fun handleAuthCode(code: String, state: String, redirectUri: String) {
        if (state != authState) {
            AppLog.e("AUTH", "handleAuthCode: state mismatch — potential CSRF attack")
            _currentScreen.value = AppScreen.AuthError("Security check failed (state mismatch)")
            return
        }
        try {
            val verifier = codeVerifier ?: run {
                AppLog.e("AUTH", "handleAuthCode: codeVerifier is null — authentication state lost")
                _currentScreen.value = AppScreen.AuthError("Authentication state lost")
                return
            }
            val tokenResponse = oAuth2Manager.exchangeCodeForTokens(
                code, verifier, redirectUri
            )
            credentialManager.storeTokens(tokenResponse)
            AppLog.step("auth", "handleAuthCode: tokens stored successfully")

            // Try fetching characters with the first token
            try {
                val characters = oAuth2Manager.fetchCharacters(tokenResponse.accessToken)
                handleCharacters(characters, tokenResponse.accessToken)
            } catch (e: OAuthException) {
                if (e.httpCode == 401 || e.httpCode == 403) {
                    // First token not sufficient — need second OAuth2 step
                    AppLog.step("auth", "handleAuthCode: Step 1 token rejected (${e.httpCode}), starting second auth step")
                    startSecondAuthStep(tokenResponse.accessToken)
                } else {
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("AUTH", "handleAuthCode: exception: ${e.message}", e)
            _currentScreen.value = AppScreen.AuthError(e.message ?: "Authentication failed")
        } finally {
            codeVerifier = null
            authState = null
        }
    }

    private suspend fun handleCharacters(characters: List<GameCharacter>, accessToken: String) {
        if (characters.isEmpty()) {
            AppLog.e("AUTH", "handleCharacters: no characters found on account")
            _currentScreen.value = AppScreen.AuthError("No characters found on this account")
            return
        }
        if (characters.size == 1) {
            AppLog.step("auth", "handleCharacters: single character, auto-selecting")
            selectCharacter(characters[0], accessToken)
        } else {
            AppLog.step("auth", "handleCharacters: ${characters.size} characters found, showing selection screen")
            pendingAccessToken = accessToken
            _currentScreen.value = AppScreen.CharacterSelect(characters)
        }
    }

    fun onCharacterSelected(character: GameCharacter) {
        viewModelScope.launch {
            try {
                val accessToken = pendingAccessToken
                    ?: credentialManager.getCredentials()?.accessToken
                    ?: run {
                        AppLog.e("AUTH", "onCharacterSelected: no access token available")
                        _currentScreen.value = AppScreen.AuthError("No access token")
                        return@launch
                    }
                selectCharacter(character, accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("AUTH", "onCharacterSelected: exception: ${e.message}", e)
                _currentScreen.value = AppScreen.AuthError(e.message ?: "Session creation failed")
            }
        }
    }

    private suspend fun selectCharacter(character: GameCharacter, accessToken: String) {
        AppLog.step("auth", "selectCharacter: creating game session for ***")
        val sessionId = oAuth2Manager.createGameSession(accessToken, character.accountId)
        credentialManager.storeGameSession(sessionId, character.accountId, character.displayName)
        pendingAccessToken = null
        AppLog.step("auth", "selectCharacter: session created and stored -> Launch")
        _currentScreen.value = AppScreen.Launch
    }

    /**
     * Skip login — proceed to launch without credentials.
     */
    fun skipLogin() {
        AppLog.step("auth", "skipLogin: skipping login, going to Launch")
        _currentScreen.value = AppScreen.Launch
    }

    // -------------------------------------------------------------------------
    // Auth: Second OAuth2 step (if Step 1 token gets 401/403)
    // -------------------------------------------------------------------------

    private suspend fun startSecondAuthStep(firstAccessToken: String) {
        val server = LocalhostAuthServer()
        try {
            val port = server.start()
            val secondVerifier = PkceHelper.generateVerifier()
            val secondState = PkceHelper.generateState()
            val secondAuthUrl = oAuth2Manager.buildAuthUrl(secondVerifier, secondState, port)
            AppLog.step("auth", "startSecondAuthStep: launching second Custom Tab on port $port")

            // Open in Custom Tab
            val customTabIntent = buildCustomTabIntent()
            customTabIntent.intent.data = secondAuthUrl
            orchestrator.actions?.launchIntent(customTabIntent.intent)

            // Wait for localhost redirect
            val result = server.awaitRedirect(120_000L)
            when (result) {
                is AuthCodeResult.Success -> {
                    if (result.state != secondState) {
                        AppLog.e("AUTH", "startSecondAuthStep: state mismatch")
                        _currentScreen.value = AppScreen.AuthError("Security check failed")
                        return
                    }
                    val secondTokens = oAuth2Manager.exchangeCodeForTokens(
                        result.code, secondVerifier, "http://localhost:$port"
                    )
                    credentialManager.storeTokens(secondTokens)
                    val characters = oAuth2Manager.fetchCharacters(secondTokens.accessToken)
                    handleCharacters(characters, secondTokens.accessToken)
                }
                is AuthCodeResult.Error ->
                    _currentScreen.value = AppScreen.AuthError(result.description)
                is AuthCodeResult.Cancelled ->
                    _currentScreen.value = AppScreen.Login
            }
        } finally {
            server.stop()
        }
    }

    // -------------------------------------------------------------------------
    // Auth: Token refresh
    // -------------------------------------------------------------------------

    /**
     * Check if the access token needs refreshing; refresh if so.
     * Called before launch to ensure credentials are fresh.
     */
    suspend fun refreshIfNeeded(): AuthResult {
        val expiry = credentialManager.getAccessTokenExpiry()
        val now = System.currentTimeMillis() / 1000L
        if (now < expiry - 60) {
            AppLog.step("auth", "refreshIfNeeded: token still valid, expires in ${expiry - now}s")
            return AuthResult.Valid
        }

        val refreshToken = credentialManager.getRefreshToken()
        if (refreshToken == null) {
            AppLog.step("auth", "refreshIfNeeded: no refresh token -> NeedsLogin")
            return AuthResult.NeedsLogin
        }

        return try {
            AppLog.step("auth", "refreshIfNeeded: token expired, refreshing")
            val response = oAuth2Manager.refreshTokens(refreshToken)
            credentialManager.storeTokens(response)
            AppLog.step("auth", "refreshIfNeeded: refresh succeeded")
            AuthResult.Refreshed
        } catch (e: OAuthException) {
            if (e.httpCode == 401) {
                AppLog.w("AUTH", "refreshIfNeeded: refresh token rejected (401) -> NeedsLogin")
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
     * Request battery optimization exemption for Termux.
     * Called from the UI when the battery optimization phase is active.
     */
    fun requestBatteryOptimization() {
        AppLog.ui("requestBatteryOptimization: requesting for Termux")
        orchestrator.requestBatteryOptimization()
        // Also request for our own app
        orchestrator.requestOwnBatteryOptimization()
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

    fun bindActions(activity: Activity) {
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
        }
    }

    fun unbindActions() {
        orchestrator.actions = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Escape a string for safe use inside single-quoted shell strings.
     * Replaces ' with '\'' so injection via credential values is not possible.
     */
    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

    /**
     * Build a CustomTabsIntent configured for the Jagex login flow.
     * Prefers Chrome if available; falls back to system default browser.
     */
    private fun buildCustomTabIntent(): CustomTabsIntent {
        val builder = CustomTabsIntent.Builder()
            .setShowTitle(true)
        val intent = builder.build()
        // Prefer Chrome if installed — guarantees Cloudflare compatibility
        val chromePackage = getChromePackage()
        if (chromePackage != null) {
            intent.intent.setPackage(chromePackage)
        }
        return intent
    }

    /**
     * Find an installed Chrome or Chrome-variant package to use for Custom Tabs.
     * Returns null if none found (fall back to system default browser).
     */
    private fun getChromePackage(): String? {
        val browsers = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )
        return browsers.firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        const val REQUEST_TERMUX_PERMISSION = 1001
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
            val orchestrator = SetupOrchestrator(
                context, termuxHelper, apkDownloader, apkInstaller,
                commandRunner, scriptManager, cleanupManager, stateStore
            )
            return SetupViewModel(
                context, orchestrator, commandRunner, scriptManager,
                credentialManager, oAuth2Manager, displayPreferences, stateStore
            ) as T
        }
    }
}

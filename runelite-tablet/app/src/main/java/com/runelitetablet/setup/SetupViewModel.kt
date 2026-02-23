package com.runelitetablet.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runelitetablet.auth.AuthResult
import com.runelitetablet.auth.ConsentResult
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
import kotlinx.coroutines.CompletableDeferred
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
    // Step 1: PKCE verifier + state nonce + deferred for jagex: scheme redirect
    @Volatile private var step1Verifier: String? = null
    @Volatile private var step1State: String? = null
    @Volatile private var step1Deferred: CompletableDeferred<Uri>? = null

    // Whether we're currently awaiting the Step 1 jagex: redirect (for dismissal detection)
    @Volatile private var awaitingStep1Auth = false

    // Active localhost server for Step 2 consent redirect capture.
    @Volatile private var activeConsentServer: LocalhostAuthServer? = null
    @Volatile private var activeLoginJob: Job? = null

    // Stored session ID between character list fetch and character selection
    @Volatile private var pendingSessionId: String? = null

    /**
     * Expose display name to the UI without leaking the full CredentialManager.
     */
    fun getDisplayName(): String? = credentialManager.getDisplayName()

    override fun onCleared() {
        super.onCleared()
        // Stop any active auth server and wipe sensitive state
        activeConsentServer?.stop()
        activeConsentServer = null
        activeLoginJob?.cancel()
        activeLoginJob = null
        step1Deferred?.cancel()
        step1Deferred = null
        step1Verifier = null
        step1State = null
        awaitingStep1Auth = false
        pendingSessionId = null
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
        // Fix 8: credentialManager I/O must run off the Main thread
        val hasCredentials = withContext(Dispatchers.IO) { credentialManager.hasCredentials() }
        if (hasCredentials) {
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
        // Fix 8: credentialManager I/O must run off the Main thread
        val creds = withContext(Dispatchers.IO) { credentialManager.getCredentials() }
        if (creds != null) {
            try {
                val file = java.io.File(
                    context.filesDir,
                    "launch_env_${System.currentTimeMillis()}.sh"
                )
                withContext(Dispatchers.IO) {
                    val content = buildString {
                        appendLine("export JX_SESSION_ID='${shellEscape(creds.sessionId)}'")
                        appendLine("export JX_CHARACTER_ID='${shellEscape(creds.characterId)}'")
                        appendLine("export JX_DISPLAY_NAME='${shellEscape(creds.displayName)}'")
                        creds.accessToken?.let { appendLine("export JX_ACCESS_TOKEN='${shellEscape(it)}'") }
                    }
                    java.io.FileOutputStream(file).use { fos ->
                        fos.write(content.toByteArray(Charsets.UTF_8))
                        fos.fd.sync()
                    }
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
    // Auth: 2-Step Login Flow
    //
    // Step 1: Account auth via launcher client + jagex: scheme redirect
    // Step 2: Consent via consent client + localhost forwarder (Jagex accounts only)
    // Step 3: Game session API calls (POST id_token, GET accounts with Bearer sessionId)
    // -------------------------------------------------------------------------

    /**
     * Called from MainActivity.onNewIntent when a jagex: URI is received.
     * This completes Step 1 by delivering the redirect URI to the waiting coroutine.
     */
    fun handleJagexRedirect(uri: Uri) {
        val deferred = step1Deferred
        if (deferred == null) {
            AppLog.w("AUTH", "handleJagexRedirect: received jagex: URI but no login in progress — ignoring")
            return
        }
        // Fix 1: Log only the scheme, not the full URI (auth code is sensitive)
        AppLog.step("auth", "handleJagexRedirect: received jagex: redirect (scheme=${uri.scheme})")
        // Fix 2: Set awaitingStep1Auth = false BEFORE completing the deferred to prevent
        // checkLoginDismissal from seeing stale true and cancelling a completed auth
        awaitingStep1Auth = false
        deferred.complete(uri)
    }

    /**
     * Start the Jagex OAuth2 login flow — correct 2-step, 2-client-ID flow.
     *
     * Step 1: Open Chrome Custom Tab with launcher client.
     *   - Jagex redirects to launcher-redirect page -> jagex: scheme -> our intent filter
     *   - onNewIntent -> handleJagexRedirect -> completes step1Deferred
     *   - Exchange code for tokens, parse id_token for login_provider
     *
     * Step 2 (Jagex accounts only): Open Chrome Custom Tab with consent client.
     *   - Redirect to localhost -> forwarder HTML extracts fragment -> POST /jws
     *   - awaitConsentRedirect captures id_token
     *
     * Step 3: Game session API calls.
     *   - POST id_token to /sessions -> sessionId
     *   - GET /accounts with Bearer sessionId -> character list
     */
    fun startLogin() {
        // Cancel any previous login attempt
        activeLoginJob?.cancel()
        activeConsentServer?.stop()
        step1Deferred?.cancel()

        activeLoginJob = viewModelScope.launch {
            try {
                // ---- STEP 1: Account authentication ----
                step1Verifier = PkceHelper.generateVerifier()
                step1State = PkceHelper.generateState()
                val deferred = CompletableDeferred<Uri>()
                step1Deferred = deferred

                val verifier = step1Verifier ?: run {
                    AppLog.e("AUTH", "startLogin: step1Verifier is null unexpectedly")
                    _currentScreen.value = AppScreen.AuthError("Authentication state lost")
                    return@launch
                }
                val state = step1State ?: run {
                    AppLog.e("AUTH", "startLogin: step1State is null unexpectedly")
                    _currentScreen.value = AppScreen.AuthError("Authentication state lost")
                    return@launch
                }

                val authUrl = oAuth2Manager.buildStep1AuthUrl(verifier, state)
                AppLog.step("auth", "startLogin: Step 1 — launching Custom Tab with launcher client")

                val actions = orchestrator.actions
                if (actions == null) {
                    AppLog.e("AUTH", "startLogin: no actions bound — activity not available")
                    _currentScreen.value = AppScreen.AuthError("Activity not available")
                    return@launch
                }

                val customTabIntent = buildCustomTabIntent()
                customTabIntent.intent.data = authUrl
                awaitingStep1Auth = true
                // Fix 6: Fall back to ACTION_VIEW if Custom Tabs are unavailable
                try {
                    actions.launchIntent(customTabIntent.intent)
                } catch (e: ActivityNotFoundException) {
                    AppLog.w("AUTH", "startLogin: Custom Tabs unavailable, falling back to ACTION_VIEW")
                    actions.launchIntent(Intent(Intent.ACTION_VIEW, authUrl))
                }

                // Wait for jagex: scheme redirect via CompletableDeferred
                val jagexUri: Uri
                try {
                    jagexUri = withTimeout(120_000L) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    AppLog.w("AUTH", "startLogin: Step 1 timed out waiting for jagex: redirect")
                    _currentScreen.value = AppScreen.AuthError("Login timed out — try again")
                    return@launch
                } finally {
                    awaitingStep1Auth = false
                    step1Deferred = null
                }

                // Parse code + state from jagex: URI
                // Format: jagex:code=XXX&state=YYY&intent=social_auth
                val uriStr = jagexUri.toString()
                val paramsStr = uriStr.removePrefix("jagex:")
                val params = parseQueryParams(paramsStr)
                val code = params["code"]
                val returnedState = params["state"]

                if (code == null) {
                    AppLog.e("AUTH", "startLogin: no code in jagex: URI")
                    _currentScreen.value = AppScreen.AuthError("No authorization code received")
                    return@launch
                }
                if (returnedState != state) {
                    AppLog.e("AUTH", "startLogin: state mismatch — potential CSRF attack")
                    _currentScreen.value = AppScreen.AuthError("Security check failed — try again")
                    return@launch
                }

                AppLog.step("auth", "startLogin: Step 1 — code received, exchanging for tokens")

                // Exchange code for tokens (with PKCE verifier)
                val tokenResponse = oAuth2Manager.exchangeCodeForTokens(code, verifier)
                // Fix 8: credentialManager I/O must run off the Main thread
                withContext(Dispatchers.IO) { credentialManager.storeTokens(tokenResponse) }
                AppLog.step("auth", "startLogin: Step 1 — tokens received and stored")

                // Parse login_provider from id_token
                val idToken = tokenResponse.idToken
                if (idToken == null) {
                    AppLog.w("AUTH", "startLogin: no id_token in Step 1 response — defaulting to jagex flow")
                }
                val loginProvider = if (idToken != null) {
                    oAuth2Manager.parseLoginProvider(idToken)
                } else {
                    "jagex"
                }
                AppLog.step("auth", "startLogin: login_provider=$loginProvider")

                when (loginProvider) {
                    "runescape" -> {
                        // Legacy RuneScape account — use Step 1 tokens directly
                        AppLog.step("auth", "startLogin: RuneScape account — skipping Step 2")
                        // For legacy accounts, store tokens as credentials and go to Launch
                        // JX_ACCESS_TOKEN and JX_REFRESH_TOKEN used directly
                        _currentScreen.value = AppScreen.Launch
                    }
                    else -> {
                        // Jagex account — proceed to Step 2 consent
                        AppLog.step("auth", "startLogin: Jagex account — proceeding to Step 2 consent")
                        val consentIdToken = runStep2Consent()
                        if (consentIdToken != null) {
                            runStep3GameSession(consentIdToken)
                        }
                        // If null, error was already shown by runStep2Consent
                    }
                }

            } catch (e: CancellationException) {
                AppLog.step("auth", "startLogin: cancelled")
                throw e
            } catch (e: OAuthException) {
                AppLog.e("AUTH", "startLogin: OAuth error: ${e.message}", e)
                _currentScreen.value = AppScreen.AuthError(e.message ?: "Authentication failed")
            } catch (e: Exception) {
                AppLog.e("AUTH", "startLogin: exception: ${e.message}", e)
                _currentScreen.value = AppScreen.AuthError(e.message ?: "Login failed")
            } finally {
                step1Verifier = null
                step1State = null
                step1Deferred = null
                awaitingStep1Auth = false
            }
        }
    }

    /**
     * Step 2: Consent flow via consent client + localhost server.
     * Opens a Chrome Custom Tab with the consent URL.
     * The redirect lands on localhost with fragment params (id_token + code).
     * Forwarder HTML extracts fragment and POSTs back to our server.
     *
     * @return The consent id_token, or null if the flow failed/was cancelled
     */
    private suspend fun runStep2Consent(): String? {
        val server = LocalhostAuthServer()
        activeConsentServer = server
        try {
            val port = server.start()
            val consentState = PkceHelper.generateState()
            val nonce = oAuth2Manager.generateNonce()
            val consentUrl = oAuth2Manager.buildStep2ConsentUrl(port, consentState, nonce)
            AppLog.step("auth", "startLogin: Step 2 — launching consent Custom Tab on port $port")

            val actions = orchestrator.actions
            if (actions == null) {
                AppLog.e("AUTH", "runStep2Consent: no actions bound — activity not available")
                _currentScreen.value = AppScreen.AuthError("Activity not available")
                return null
            }

            val customTabIntent = buildCustomTabIntent()
            customTabIntent.intent.data = consentUrl
            // Fix 6: Fall back to ACTION_VIEW if Custom Tabs are unavailable
            try {
                actions.launchIntent(customTabIntent.intent)
            } catch (e: ActivityNotFoundException) {
                AppLog.w("AUTH", "runStep2Consent: Custom Tabs unavailable, falling back to ACTION_VIEW")
                actions.launchIntent(Intent(Intent.ACTION_VIEW, consentUrl))
            }

            // Wait for the 2-request consent flow (forwarder HTML + POST /jws)
            val result = server.awaitConsentRedirect(consentState, 120_000L)
            activeConsentServer = null

            return when (result) {
                is ConsentResult.Success -> {
                    AppLog.step("auth", "startLogin: Step 2 — consent id_token received")
                    // Fix 4: Validate nonce in the consent id_token to prevent replay attacks
                    if (!oAuth2Manager.verifyNonce(result.idToken, nonce)) {
                        throw OAuthException(0, "Security check failed - try again", "nonce_mismatch")
                    }
                    result.idToken
                }
                is ConsentResult.Error -> {
                    AppLog.e("AUTH", "runStep2Consent: error: ${result.error} — ${result.description}")
                    _currentScreen.value = AppScreen.AuthError("Consent failed: ${result.description}")
                    null
                }
                is ConsentResult.Cancelled -> {
                    AppLog.step("auth", "runStep2Consent: consent timed out or cancelled")
                    _currentScreen.value = AppScreen.AuthError("Consent timed out — try again")
                    null
                }
            }
        } finally {
            server.stop()
            if (activeConsentServer === server) activeConsentServer = null
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
            _currentScreen.value = AppScreen.Launch
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
                _currentScreen.value = AppScreen.Launch
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
     * Called from onResume() to detect Custom Tab dismissal.
     * If a login is in progress and the user returns without completing auth,
     * cancel the login after a short grace period.
     */
    fun checkLoginDismissal() {
        val job = activeLoginJob ?: return
        if (!job.isActive) return

        // Check Step 1 (jagex: scheme redirect)
        if (awaitingStep1Auth) {
            viewModelScope.launch {
                delay(1000) // Longer grace period — jagex: redirect may take a moment
                if (awaitingStep1Auth && job.isActive) {
                    AppLog.step("auth", "checkLoginDismissal: Step 1 Custom Tab dismissed — cancelling login")
                    job.cancel()
                    activeLoginJob = null
                    awaitingStep1Auth = false
                    step1Deferred?.cancel()
                    step1Deferred = null
                    // Fix 7: Show "Login cancelled" message so the user knows why they landed here
                    _currentScreen.value = AppScreen.AuthError("Login cancelled — tap Sign In to try again")
                }
            }
            return
        }

        // Check Step 2 (consent server)
        val server = activeConsentServer ?: return
        viewModelScope.launch {
            // Fix 3: 2000ms grace period — the 2-request JS flow (serve HTML -> browser executes
            // JS -> POST /jws) needs more time than a simple redirect
            delay(2000)
            if (activeConsentServer === server && job.isActive) {
                AppLog.step("auth", "checkLoginDismissal: Step 2 Custom Tab dismissed — cancelling login")
                job.cancel()
                activeConsentServer = null
                activeLoginJob = null
                server.stop()
                // Fix 7: Show "Login cancelled" message so the user knows why they landed here
                _currentScreen.value = AppScreen.AuthError("Login cancelled — tap Sign In to try again")
            }
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
     * Parse query parameters from a string like "code=XXX&state=YYY&intent=social_auth".
     * Used to parse the jagex: URI which comes as jagex:code=XXX&state=YYY (no standard query format).
     */
    private fun parseQueryParams(paramsStr: String): Map<String, String> {
        if (paramsStr.isBlank()) return emptyMap()
        return paramsStr.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else {
                null
            }
        }.toMap()
    }

    /**
     * Escape a string for safe use inside single-quoted shell strings.
     * Replaces ' with '\'' so injection via credential values is not possible.
     */
    // Fix 5: Escape backslashes BEFORE single quotes — otherwise a backslash immediately before
    // a single quote would be double-escaped in the wrong order
    private fun shellEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "'\\''")

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

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
import com.runelitetablet.auth.CustomTabAuthCapture
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
}

class SetupViewModel(
    private val context: Context,
    private val orchestrator: SetupOrchestrator,
    private val commandRunner: TermuxCommandRunner,
    private val scriptManager: ScriptManager,
    private val credentialManager: CredentialManager,
    private val oAuth2Manager: JagexOAuth2Manager
) : ViewModel() {

    val steps: StateFlow<List<StepState>> = orchestrator.steps
    val currentOutput: StateFlow<String?> = orchestrator.currentOutput

    val canLaunch: StateFlow<Boolean> = orchestrator.steps
        .map { steps -> steps.all { it.status is StepStatus.Completed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _showPermissionsSheet = MutableStateFlow(false)
    val showPermissionsSheet: StateFlow<Boolean> = _showPermissionsSheet.asStateFlow()

    private val _permissionInstructions = MutableStateFlow<List<String>>(emptyList())
    val permissionInstructions: StateFlow<List<String>> = _permissionInstructions.asStateFlow()

    private val _currentScreen = MutableStateFlow<AppScreen>(AppScreen.Setup)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val setupStarted = AtomicBoolean(false)
    private val isRetrying = AtomicBoolean(false)

    // Auth state — lives only for the duration of one login attempt
    @Volatile private var codeVerifier: String? = null
    @Volatile private var authState: String? = null
    @Volatile private var customTabCapture: CustomTabAuthCapture? = null

    // Stored access token between character list fetch and character selection
    @Volatile private var pendingAccessToken: String? = null

    /**
     * Expose display name to the UI without leaking the full CredentialManager.
     */
    fun getDisplayName(): String? = credentialManager.getDisplayName()

    override fun onCleared() {
        super.onCleared()
        // Wipe sensitive auth state when ViewModel is destroyed
        pendingAccessToken = null
        codeVerifier = null
        authState = null
        customTabCapture?.unbind(context)
        customTabCapture = null
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
     */
    private fun launch(envFilePath: String? = null) {
        AppLog.perf("launch: started")

        // Check Termux:X11 is installed before anything else
        val termuxHelper = TermuxPackageHelper(context)
        if (!termuxHelper.isTermuxX11Installed()) {
            AppLog.e("STEP", "launch: Termux:X11 not installed — cannot launch")
            return
        }

        // Set Termux:X11 preferences before launch so the display is fullscreen from the start.
        val prefIntent = Intent("com.termux.x11.CHANGE_PREFERENCE").apply {
            setPackage("com.termux.x11")
            putExtra("fullscreen", "true")
            putExtra("showAdditionalKbd", "false")
            putExtra("displayResolutionMode", "native")
        }
        context.sendBroadcast(prefIntent)
        AppLog.step("launch", "launch: sent CHANGE_PREFERENCE broadcast to Termux:X11")

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
    }

    /**
     * Public launch entry point called from the UI.
     * Refreshes tokens if needed, writes env file, then calls internal launch().
     */
    fun launchRuneLite() {
        viewModelScope.launch {
            // Pre-launch token refresh
            if (credentialManager.hasCredentials()) {
                when (val result = refreshIfNeeded()) {
                    is AuthResult.NeedsLogin -> {
                        AppLog.step("auth", "launchRuneLite: token expired, need re-login")
                        _currentScreen.value = AppScreen.Login
                        return@launch
                    }
                    is AuthResult.NetworkError -> {
                        // Log but continue — existing credentials may still work
                        AppLog.w("AUTH", "launchRuneLite: refresh failed: ${result.exception.message}")
                    }
                    else -> {} // Valid or Refreshed — proceed
                }
            }

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
                    AppLog.step("auth", "launchRuneLite: env file written to $envFilePath (credentials masked)")
                } catch (e: Exception) {
                    AppLog.e("AUTH", "launchRuneLite: failed to write env file: ${e.message}", e)
                }
            }

            try {
                launch(envFilePath)
            } finally {
                // Clean up env file after a short delay to ensure Termux has time to read it
                val capturedEnvFile = envFile
                if (capturedEnvFile != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(5000)
                        capturedEnvFile.delete()
                        AppLog.step("auth", "launchRuneLite: env file deleted")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auth: Login flow
    // -------------------------------------------------------------------------

    /**
     * Start the Jagex OAuth2 login flow via Chrome Custom Tab.
     */
    fun startLogin() {
        codeVerifier = PkceHelper.generateVerifier()
        authState = PkceHelper.generateState()

        val capture = CustomTabAuthCapture()
        customTabCapture = capture

        val chromePackage = getChromePackage()
        capture.bindService(context, chromePackage)

        // Build auth URL and launch Custom Tab
        val verifier = codeVerifier ?: run {
            AppLog.e("AUTH", "startLogin: codeVerifier is null unexpectedly")
            _currentScreen.value = AppScreen.AuthError("Authentication state lost")
            return
        }
        val state = authState ?: run {
            AppLog.e("AUTH", "startLogin: authState is null unexpectedly")
            _currentScreen.value = AppScreen.AuthError("Authentication state lost")
            return
        }
        val authUrl = oAuth2Manager.buildAuthUrl(verifier, state)
        val customTabIntent = capture.buildCustomTabIntent(authUrl, chromePackage)
        customTabIntent.intent.data = authUrl
        orchestrator.actions?.launchIntent(customTabIntent.intent)
        AppLog.step("auth", "startLogin: launched Custom Tab for auth")

        // Listen for the redirect asynchronously
        viewModelScope.launch {
            try {
                val result = withTimeout(300_000L) { // 5 minute timeout
                    capture.authCodeDeferred.await()
                }
                when (result) {
                    is AuthCodeResult.Success ->
                        handleAuthCode(result.code, result.state)
                    is AuthCodeResult.Error ->
                        _currentScreen.value = AppScreen.AuthError("${result.error}: ${result.description}")
                    is AuthCodeResult.Cancelled ->
                        _currentScreen.value = AppScreen.Login
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.w("AUTH", "startLogin: auth timed out after 5 minutes")
                _currentScreen.value = AppScreen.AuthError("Login timed out")
            } catch (e: CancellationException) {
                throw e
            } finally {
                capture.unbind(context)
                customTabCapture = null
            }
        }
    }

    private suspend fun handleAuthCode(code: String, state: String) {
        if (state != authState) {
            AppLog.e("AUTH", "handleAuthCode: state mismatch — potential CSRF attack")
            _currentScreen.value = AppScreen.AuthError("Security check failed (state mismatch)")
            return
        }
        try {
            // Step 1: Exchange code for tokens
            val verifier = codeVerifier ?: run {
                AppLog.e("AUTH", "handleAuthCode: codeVerifier is null — authentication state lost")
                _currentScreen.value = AppScreen.AuthError("Authentication state lost")
                return
            }
            val tokenResponse = oAuth2Manager.exchangeCodeForTokens(
                code, verifier, JagexOAuth2Manager.REDIRECT_URI
            )
            credentialManager.storeTokens(tokenResponse)
            AppLog.step("auth", "handleAuthCode: tokens stored successfully")

            // Try Step 1 direct: fetch characters with first token
            try {
                val characters = oAuth2Manager.fetchCharacters(tokenResponse.accessToken)
                handleCharacters(characters, tokenResponse.accessToken)
            } catch (e: OAuthException) {
                if (e.httpCode == 401 || e.httpCode == 403) {
                    // Step 1 token not sufficient — need second OAuth2 step
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

    /**
     * Handle redirect URI from onNewIntent (fallback if CustomTabsCallback misses it).
     */
    fun onAuthRedirect(uri: Uri) {
        AppLog.step("auth", "onAuthRedirect: received redirect URI from onNewIntent")
        customTabCapture?.handleRedirectUri(uri)
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
            val secondAuthUrl = oAuth2Manager.buildSecondAuthUrl(secondVerifier, secondState, port)
            AppLog.step("auth", "startSecondAuthStep: launching second Custom Tab on port $port")

            // Open in Custom Tab
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
            intent.intent.data = secondAuthUrl
            orchestrator.actions?.launchIntent(intent.intent)

            // Wait for localhost redirect
            val result = server.awaitRedirect(120_000L)
            when (result) {
                is AuthCodeResult.Success -> {
                    if (result.state != secondState) {
                        AppLog.e("AUTH", "startSecondAuthStep: state mismatch")
                        _currentScreen.value = AppScreen.AuthError("Security check failed")
                        return
                    }
                    val secondTokens = oAuth2Manager.exchangeSecondCode(
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
    // Manual step / permissions
    // -------------------------------------------------------------------------

    fun onManualStepClick(stepState: StepState) {
        val status = stepState.status
        AppLog.state("onManualStepClick: stepId=${stepState.step.id} status=${status::class.simpleName}")
        if (status is StepStatus.ManualAction) {
            _permissionInstructions.value = status.instructions
            _showPermissionsSheet.value = true
            AppLog.state("onManualStepClick: showing permissions sheet instructionCount=${status.instructions.size}")
        }
    }

    fun dismissPermissionsSheet() {
        AppLog.state("dismissPermissionsSheet: hiding permissions sheet")
        _showPermissionsSheet.value = false
    }

    fun verifyPermissions() {
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                _showPermissionsSheet.value = false
                val verified = orchestrator.verifyPermissions()
                if (verified) {
                    orchestrator.retryCurrentStep()
                }
            } finally {
                isRetrying.set(false)
            }
        }
    }

    fun recheckPermissions() {
        // Re-evaluate completed steps on return from settings/install screens.
        // Guard with isRetrying to prevent concurrent retryCurrentStep() calls if the user
        // navigates back rapidly (e.g., quick back-press from install confirmation screen).
        if (!isRetrying.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
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
            val orchestrator = SetupOrchestrator(
                context, termuxHelper, apkDownloader, apkInstaller,
                commandRunner, scriptManager, cleanupManager, stateStore
            )
            return SetupViewModel(
                context, orchestrator, commandRunner, scriptManager,
                credentialManager, oAuth2Manager
            ) as T
        }
    }
}

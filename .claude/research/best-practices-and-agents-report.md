# RuneLite for Tablet: Best Practices & Agent Design Report

**Date**: 2026-02-22
**Scope**: Technology best practices + Claude Code agent/skill design recommendations

---

## Part 1: Technology Best Practices

### 1. Kotlin Coroutines on Android

#### Current State in the Codebase

The project uses coroutines correctly in several places: `viewModelScope.launch` for UI-triggered work, `withContext(Dispatchers.IO)` for network calls in `ApkDownloader`, and `CompletableDeferred` for bridging callback-based APIs (Termux results, PackageInstaller). However, there are specific improvements worth making.

#### Best Practices

**Structured Concurrency & Scope Selection**

| Scope | When to Use | Lifecycle |
|-------|-------------|-----------|
| `viewModelScope` | ViewModel operations | Cleared with ViewModel |
| `lifecycleScope` | UI-layer operations in Activity/Fragment | Destroyed with lifecycle |
| `repeatOnLifecycle` | Flow collection in UI | Starts/stops with lifecycle state |
| Custom injected scope | App-wide background work | Application lifetime |
| `GlobalScope` | **NEVER** | Breaks structured concurrency |

The project correctly uses `viewModelScope` in `SetupViewModel`. No issues here.

**Dispatcher Injection for Testability**

The current code hardcodes `Dispatchers.IO` in `ApkDownloader.download()`:

```kotlin
// CURRENT (not testable)
suspend fun download(...): File = withContext(Dispatchers.IO) { ... }

// BETTER: inject dispatcher
class ApkDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun download(...): File = withContext(ioDispatcher) { ... }
}
```

This lets tests pass a `StandardTestDispatcher` to control timing.

**CancellationException Handling**

The project has `try/catch(e: Exception)` blocks in `SetupOrchestrator.runSetup()` (lines 67-68) and `retryCurrentStep()` (lines 95-98). These will swallow `CancellationException`, preventing coroutine cancellation from propagating properly.

```kotlin
// CURRENT (swallows cancellation)
try {
    val success = executeStep(stepState.step)
    ...
} catch (e: Exception) {
    updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
}

// FIX: rethrow CancellationException
try {
    val success = executeStep(stepState.step)
    ...
} catch (e: CancellationException) {
    throw e  // Must rethrow
} catch (e: Exception) {
    updateStepStatus(index, StepStatus.Failed(e.message ?: "Unknown error"))
}
```

This is important because when the user navigates away or the ViewModel is cleared, `viewModelScope` cancels child coroutines. If `CancellationException` is swallowed, the setup keeps running in a zombie state.

**CompletableDeferred Timeout & Cleanup**

The `TermuxCommandRunner.execute()` correctly uses `withTimeout` and cleans up the pending result on timeout. The `ApkInstaller.install()` does NOT have a timeout on its `deferred.await()`:

```kotlin
// CURRENT: no timeout, will wait forever if broadcast never arrives
return deferred.await()

// FIX: add timeout
return withTimeout(120_000) { deferred.await() }
```

**Flow Collection in Compose**

The project uses `collectAsState()` in Compose, which is the correct pattern for Compose-based apps. However, if you later need to collect flows in Activity lifecycle methods, use `repeatOnLifecycle`:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.steps.collect { ... }
    }
}
```

The `LaunchedEffect(Unit) { viewModel.startSetup() }` in `SetupScreen` is correct for triggering a one-time side effect.

#### Specific Pitfalls for This Project

1. **Long-running setup operations**: The setup script can take 10+ minutes. If Android kills the process, the `CompletableDeferred` is lost. Consider persisting setup state to survive process death.
2. **Termux service start races**: `context.startService(intent)` is async. The service may not process the command before the app's coroutine timeout. The current 10-minute timeout is generous enough, but worth monitoring.
3. **`collect` in `init` block**: `SetupViewModel.init` launches a coroutine that collects `orchestrator.steps` forever. This is fine because `viewModelScope` will cancel it, but switching to `stateIn` would be cleaner:

```kotlin
val canLaunch: StateFlow<Boolean> = orchestrator.steps
    .map { steps -> steps.all { it.status is StepStatus.Completed } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
```

---

### 2. Jetpack Compose Patterns

#### Current State

The project has a clean single-screen Compose setup with proper state hoisting (ViewModel exposes `StateFlow`, screen collects via `collectAsState`). The UI is simple enough that most advanced Compose pitfalls don't apply yet.

#### Best Practices

**State Hoisting**

The project correctly follows state hoisting: `SetupScreen` receives `SetupViewModel`, all state flows through `StateFlow`. One improvement: the `SetupScreen` takes the whole ViewModel as a parameter. For testability and previews, consider passing individual state values and callbacks:

```kotlin
// MORE TESTABLE (for future)
@Composable
fun SetupScreen(
    steps: List<StepState>,
    canLaunch: Boolean,
    currentOutput: String?,
    onRetry: () -> Unit,
    onLaunch: () -> Unit,
    onManualStepClick: (StepState) -> Unit,
    // ...
)
```

This enables `@Preview` composables and makes Compose UI testing straightforward.

**Recomposition Awareness**

Current code is mostly fine because the UI is simple. Watch for these as complexity grows:

- `steps.forEach { ... }` in `SetupScreen` will recompose ALL items when any step changes. For 7 items this is negligible, but if you add scrollable lists later, use `LazyColumn` with stable `key`:

```kotlin
LazyColumn {
    items(steps, key = { it.step.id }) { stepState ->
        StepItem(...)
    }
}
```

- The `currentOutput!!` force-unwrap inside `if (currentOutput != null)` is safe due to the check, but could be cleaner with `?.let`:

```kotlin
currentOutput?.let { output ->
    OutputCard(text = output, isError = steps.any { it.status is StepStatus.Failed })
}
```

**Side Effects**

`LaunchedEffect(Unit) { viewModel.startSetup() }` is correct for one-time setup. If you need effects that respond to state changes, use `LaunchedEffect(key)` with the appropriate key to restart when needed.

**Compose Testing Strategy**

Add these testing dependencies when you're ready:
```kotlin
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

Test composables in isolation by passing state directly:
```kotlin
@Test
fun stepItem_showsCompleted() {
    composeTestRule.setContent {
        StepItem(
            label = "Install Termux",
            status = StepStatus.Completed,
            onClick = {}
        )
    }
    composeTestRule.onNodeWithText("Install Termux").assertIsDisplayed()
}
```

#### Pitfalls to Avoid

1. **Backwards writes**: Never write to state that has already been read in the same composition. This causes infinite recomposition loops.
2. **Heavy work in composition**: If you add data formatting or filtering, wrap in `remember(key) { ... }`.
3. **Unstable types in state**: `List<StepState>` is technically unstable for Compose. Consider adding `@Immutable` annotation to `StepState` and `StepStatus`, or using `kotlinx.collections.immutable.ImmutableList`.

---

### 3. Android PackageInstaller API

#### Current State

The `ApkInstaller` implementation is solid. It correctly:
- Uses session-based `PackageInstaller` (not deprecated `ACTION_INSTALL_PACKAGE`)
- Handles `FLAG_MUTABLE` for Android 12+
- Uses `fsync()` after writing APK bytes
- Handles `NeedsUserAction` for user confirmation

#### Best Practices & Edge Cases

**Session Lifecycle Management**

The current code creates a session and immediately writes to it. There's no handling for abandoned sessions. Android's PackageInstaller can accumulate abandoned sessions that consume storage:

```kotlin
// Clean up abandoned sessions on app start
fun cleanupAbandonedSessions(context: Context) {
    val installer = context.packageManager.packageInstaller
    for (session in installer.mySessions) {
        try {
            installer.abandonSession(session.sessionId)
        } catch (e: Exception) {
            // Session may already be finalized
        }
    }
}
```

Call this in `RuneLiteTabletApp.onCreate()`.

**Android 11 Process Kill Edge Case**

On Android 11, when `REQUEST_INSTALL_PACKAGES` permission status changes, the system may kill your process. When the user returns, `PackageInstallerActivity` appears on top. The current "tap Retry" flow handles this acceptably, but you could improve UX by checking install status in `onResume`:

```kotlin
override fun onResume() {
    super.onResume()
    // Check if the package is now installed
    if (termuxHelper.isTermuxInstalled()) {
        viewModel.recheckPermissions()
    }
}
```

This is already partially done in the current code.

**Signing Key Conflicts**

The code already detects `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (line 198-199). This is critical because Termux from F-Droid uses a different signing key than Termux from GitHub. The error message could be improved:

```kotlin
"Signing key conflict: Termux from F-Droid uses a different signing key. " +
"Please uninstall the existing Termux (Settings > Apps > Termux > Uninstall), " +
"then tap Retry. NOTE: This will remove your Termux data."
```

**PendingIntent Safety**

The current code uses `FLAG_UPDATE_CURRENT`, which means if two installs overlap, the second PendingIntent replaces the first. Since installs are sequential in this app, this is fine. But if you add parallel installs later, use unique request codes tied to session IDs (already done: `PendingIntent.getBroadcast(context, sessionId, ...)`).

---

### 4. OkHttp Best Practices

#### Current State

The `RuneLiteTabletApp` creates a single `OkHttpClient` with reasonable timeouts (30s connect, 5m read for large APK downloads). The client is shared via the Application class, which is correct (OkHttp clients should be singletons for connection pool reuse).

#### Improvements

**Retry Interceptor for GitHub API**

GitHub's API can return 502/503 during load spikes. Add a retry interceptor:

```kotlin
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastException: IOException? = null
        val request = chain.request()

        repeat(maxRetries) { attempt ->
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || response.code !in listOf(502, 503, 504, 429)) {
                    return response
                }
                response.close()

                if (response.code == 429) {
                    // Respect rate limit header
                    val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 60
                    Thread.sleep(retryAfter * 1000)
                } else {
                    Thread.sleep((1L shl attempt) * 1000) // Exponential: 1s, 2s, 4s
                }
            } catch (e: IOException) {
                lastException = e
                Thread.sleep((1L shl attempt) * 1000)
            }
        }
        throw lastException ?: IOException("Max retries exceeded")
    }
}
```

**GitHub API Rate Limiting**

Unauthenticated GitHub API requests are limited to 60/hour. For an installer that may be retried multiple times, this matters. Options:
1. Cache release metadata (current code already caches APK files by size)
2. Add `If-None-Match` / `ETag` headers to avoid consuming rate limit on unchanged responses
3. Consider a GitHub personal access token (optional, only if rate limiting becomes a real problem)

**User-Agent Header**

GitHub recommends setting a `User-Agent` header:

```kotlin
httpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", "RuneLiteTablet/${BuildConfig.VERSION_NAME}")
                .build()
        )
    }
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .build()
```

**Certificate Pinning (NOT Recommended)**

For this project, certificate pinning the GitHub API is more trouble than it's worth. GitHub rotates certificates, and a pinned cert will break your app when that happens. The default certificate chain validation is sufficient.

**Download Resumption**

For large APK downloads (Termux is ~40MB), consider supporting HTTP Range requests for resumable downloads:

```kotlin
if (apkFile.exists() && apkFile.length() < asset.size) {
    // Resume download
    val request = Request.Builder()
        .url(asset.downloadUrl)
        .header("Range", "bytes=${apkFile.length()}-")
        .build()
    // ... append to existing file
}
```

---

### 5. Termux Plugin API (RUN_COMMAND Intent)

#### Current State

The implementation is thorough and handles the major cases: background execution with PendingIntent callbacks, foreground launch for RuneLite, timeout handling, and execution ID tracking.

#### Best Practices & Hardening

**Permission Verification Order**

The current flow checks permissions at step 3 and falls through to a manual action. Improve robustness:

1. Check if Termux is even running: `activityManager.getRunningServices()` is deprecated, but you can try sending a simple intent and seeing if it fails
2. Check the `allow-external-apps` property indirectly by attempting a command
3. Check the `RUN_COMMAND` permission grant

**Stdout/Stderr Truncation**

Termux truncates stdout+stderr at ~100KB combined. For long setup scripts, this means you may lose error output. Add a workaround:

```bash
# In setup-environment.sh, tee output to a file
exec > >(tee /data/data/com.termux/files/home/setup.log) 2>&1
```

Then if the truncated output doesn't contain enough info, read the log file:

```kotlin
// Fallback: read full log if stderr was truncated
if (result.stderr?.endsWith("...") == true) {
    val logResult = commandRunner.execute(
        commandPath = "${TERMUX_BIN_PATH}/cat",
        arguments = arrayOf("${TERMUX_HOME_PATH}/setup.log"),
        timeoutMs = TIMEOUT_VERIFY_MS
    )
    // Use logResult.stdout for full error context
}
```

**Service Foreground Restriction**

On Android 12+, `context.startService()` can fail if the app is in the background. The current code catches this, but the error message could be more specific:

```kotlin
} catch (e: ForegroundServiceStartNotAllowedException) {
    // Android 12+ specific
    return TermuxResult(error = "Cannot start Termux from background. Please open the app first.")
} catch (e: Exception) {
    return TermuxResult(error = "Failed to start Termux service: ${e.message}")
}
```

**TermuxResultService Memory Leak Prevention**

The `pendingResults` `ConcurrentHashMap` is static and will hold `CompletableDeferred` references until they're completed or the process dies. Add cleanup for timed-out entries:

```kotlin
// In TermuxCommandRunner.execute(), the timeout block already removes the entry:
} catch (e: Exception) {
    TermuxResultService.pendingResults.remove(executionId)
    // ... already done correctly
}
```

This is already handled correctly. The only concern is if the service receives a result after the timeout already cleaned up the deferred -- this is also handled since `pendingResults.remove(executionId)?.complete(result)` uses the null-safe `?.`.

**Hardcoded Path Brittleness**

The constant `TERMUX_BIN_PATH = "/data/data/com.termux/files/usr/bin"` is correct for standard Termux installations. However, some Samsung devices use different data paths for multi-user or Secure Folder profiles. Consider querying the path from Termux's package info:

```kotlin
fun getTermuxBinPath(context: Context): String {
    return try {
        val info = context.packageManager.getApplicationInfo("com.termux", 0)
        "${info.dataDir}/files/usr/bin"
    } catch (e: Exception) {
        "/data/data/com.termux/files/usr/bin" // fallback
    }
}
```

---

### 6. Shell Scripting in Proot

#### Current State

Both scripts use `set -euo pipefail` (good) and have basic idempotency checks (checking if Ubuntu is already installed). The launch script handles PulseAudio gracefully with `|| true`.

#### Reliability Improvements

**Network Failure Resilience**

`apt-get` and `wget` can fail on spotty connections. Add retries:

```bash
# Retry wrapper function
retry() {
    local max_attempts=3
    local attempt=1
    local delay=5
    while [ $attempt -le $max_attempts ]; do
        if "$@"; then
            return 0
        fi
        echo "Attempt $attempt/$max_attempts failed, retrying in ${delay}s..."
        sleep $delay
        delay=$((delay * 2))
        attempt=$((attempt + 1))
    done
    echo "FAILED after $max_attempts attempts: $*"
    return 1
}

# Usage
retry apt-get update
retry apt-get install -y openjdk-11-jdk wget ca-certificates
retry wget -O /root/runelite/RuneLite.jar "$RUNELITE_URL"
```

**Full Idempotency**

The setup script doesn't check whether Java or RuneLite are already installed:

```bash
# Check Java
if ! proot-distro login ubuntu -- which java > /dev/null 2>&1; then
    echo "=== Installing Java ==="
    proot-distro login ubuntu -- bash -c '
        set -euo pipefail
        apt-get update
        apt-get install -y openjdk-11-jdk wget ca-certificates
    '
else
    echo "Java already installed, skipping"
fi

# Check RuneLite
if ! proot-distro login ubuntu -- test -f /root/runelite/RuneLite.jar; then
    echo "=== Downloading RuneLite ==="
    proot-distro login ubuntu -- bash -c '...'
else
    echo "RuneLite already downloaded, skipping"
fi
```

**Progress Markers**

For parsing progress in the Android app, use structured markers:

```bash
echo "PROGRESS:1/5:Updating packages"
echo "PROGRESS:2/5:Installing Ubuntu"
echo "PROGRESS:3/5:Installing Java"
echo "PROGRESS:4/5:Downloading RuneLite"
echo "PROGRESS:5/5:Installing X11"
```

The app can parse these to update individual step progress.

**Disk Space Checking**

```bash
# Check available space (need ~2GB for full setup)
AVAILABLE_KB=$(df /data/data/com.termux 2>/dev/null | awk 'NR==2{print $4}')
NEEDED_KB=2097152  # 2GB in KB
if [ -n "$AVAILABLE_KB" ] && [ "$AVAILABLE_KB" -lt "$NEEDED_KB" ]; then
    echo "ERROR: Insufficient disk space. Need ~2GB, have $(($AVAILABLE_KB/1024))MB"
    exit 1
fi
```

**Lock File for Concurrent Runs**

If the user taps "Retry" while the script is still running (race condition with timeout):

```bash
LOCKFILE="/data/data/com.termux/files/home/.setup.lock"
if [ -f "$LOCKFILE" ]; then
    PID=$(cat "$LOCKFILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "Setup is already running (PID $PID). Wait for it to finish."
        exit 1
    fi
fi
echo $$ > "$LOCKFILE"
trap 'rm -f "$LOCKFILE"' EXIT
```

**Launch Script Improvements**

```bash
# Check if X11 display is already running before starting
if ! pgrep -f "termux-x11 :0" > /dev/null 2>&1; then
    termux-x11 :0 &
    sleep 2
fi

# Verify X11 is actually running
if ! pgrep -f "termux-x11" > /dev/null 2>&1; then
    echo "ERROR: Termux:X11 failed to start"
    exit 1
fi
```

---

### 7. OAuth2 on Android (Slice 2 Preparation)

#### Architecture Decision

Given the existing research in `.claude/research/authentication-solutions.md`, the project has two viable paths:

**Option A: AppAuth Library (Recommended)**
- Uses Custom Tabs for the OAuth2 browser flow (not WebView)
- Handles PKCE automatically
- Manages token refresh
- Well-tested with many OAuth2 providers
- Jagex uses standard OAuth2, so AppAuth should work

**Option B: Manual Implementation**
- More control over the flow
- Can use Jagex's Android TWA (Trusted Web Activity) flow directly
- Less library overhead

**Recommendation**: Use AppAuth for the OAuth2 flow, then extract the tokens and pass them to RuneLite as environment variables via the launch script.

#### Token Storage

```kotlin
// Use EncryptedSharedPreferences for token storage
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val securePrefs = EncryptedSharedPreferences.create(
    context,
    "jagex_tokens",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// Store tokens
securePrefs.edit()
    .putString("jx_session_id", sessionId)
    .putString("jx_character_id", characterId)
    .putString("jx_display_name", displayName)
    .apply()
```

#### Token Flow for RuneLite

```
1. User taps "Sign in with Jagex Account"
2. AppAuth opens Custom Tab to https://account.jagex.com/oauth2/auth
3. User authenticates (Jagex handles captcha/2FA)
4. Redirect returns auth code to our app
5. Exchange code for tokens (with PKCE verifier)
6. Store tokens in EncryptedSharedPreferences
7. On RuneLite launch, inject as env vars:
   proot-distro login ubuntu --bind ... -- env \
     JX_SESSION_ID="..." JX_CHARACTER_ID="..." JX_DISPLAY_NAME="..." \
     java -jar RuneLite.jar --insecure-write-credentials
8. RuneLite saves to credentials.properties for future launches
```

#### Key Considerations

- `JX_SESSION_ID` reportedly does not expire, so one-time auth may suffice
- `--insecure-write-credentials` allows RuneLite to persist tokens itself
- After first successful auth+launch, RuneLite can auto-login from `credentials.properties`
- Token refresh should happen at launch time if tokens are expired

---

### 8. Android Security

#### Current State Assessment

The project has a reasonable security posture for Slice 1:
- `TermuxResultService` is `exported="false"` (correct)
- `InstallResultReceiver` is `exported="false"` (correct)
- Uses `REQUEST_INSTALL_PACKAGES` permission properly
- Uses `PendingIntent.FLAG_MUTABLE` only where required (Termux API needs it)

#### Security Improvements

**Intent Validation**

The `TermuxResultService` accepts any intent with an execution ID. Add validation:

```kotlin
private fun handleResult(intent: Intent, startId: Int) {
    // Verify the intent came from our PendingIntent
    val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
    if (executionId == -1 || !pendingResults.containsKey(executionId)) {
        stopSelf(startId)
        return  // Reject unknown execution IDs
    }
    // ... process result
}
```

**APK Cache Cleanup**

Downloaded APKs sit in `cacheDir/apks/` indefinitely. Clean up after successful installation:

```kotlin
// After successful install
if (result is InstallResult.Success) {
    apkFile.delete()
}
```

**Exported Components Audit**

The `MainActivity` is `exported="true"` (required for launcher). All other components should be `exported="false"`. Verify the manifest doesn't accidentally export anything.

**Network Security Config (for Slice 2)**

When you add OAuth2, create `res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.github.com</domain>
        <domain includeSubdomains="true">account.jagex.com</domain>
        <domain includeSubdomains="true">auth.jagex.com</domain>
    </domain-config>
</network-security-config>
```

**ProGuard/R8 for Release**

Currently `isMinifyEnabled = false`. Before distribution (Slice 5), enable it:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Add keep rules for serialization classes:

```proguard
-keep class com.runelitetablet.installer.GitHubRelease { *; }
-keep class com.runelitetablet.installer.ReleaseAsset { *; }
```

---

## Part 2: Claude Code Agent & Skill Design

### Design Philosophy

Based on research into Claude Code's skill architecture, the most effective agents and skills for this project should be:

1. **Concise** -- under 500 lines per SKILL.md, with progressive disclosure for reference material
2. **Specific to THIS project** -- not generic Android advice, but patterns tied to proot, Termux, PackageInstaller, etc.
3. **Action-oriented** -- each skill does one thing well
4. **Feedback-loop driven** -- include validation steps

### Recommended Skills

#### Skill 1: `code-review` (Code Review Agent)

**Purpose**: Automated review of Kotlin/Compose code changes before committing.

**When to trigger**: Before any commit, or when asked to review code.

**Directory structure**:
```
.claude/skills/code-review/
├── SKILL.md
├── android-patterns.md       # Android-specific anti-patterns
└── project-conventions.md    # This project's conventions
```

**SKILL.md outline**:

```yaml
---
name: code-review
description: "Reviews Kotlin/Compose code for Android anti-patterns, coroutine misuse, lifecycle issues, Termux API safety, and project conventions. Use when reviewing code changes or before commits."
---
```

**What it should check** (in priority order):

1. **Coroutine Safety**
   - CancellationException swallowed in catch blocks
   - Hardcoded dispatchers (should be injected)
   - Missing timeouts on CompletableDeferred.await()
   - GlobalScope usage
   - Flows collected without lifecycle awareness

2. **Compose Correctness**
   - Backwards writes (writing state read in same composition)
   - Heavy work in composition without `remember`
   - Unstable types passed to composables
   - Missing `key` in `LazyColumn` items

3. **Termux API Safety**
   - PendingIntent flag correctness (FLAG_MUTABLE on 12+)
   - Execution ID collision potential
   - Missing timeout on Termux command execution
   - Stdout/stderr truncation handling

4. **PackageInstaller Safety**
   - Session cleanup for abandoned sessions
   - Permission check before install attempt
   - Signing key conflict detection

5. **Security**
   - Exported components that shouldn't be
   - Sensitive data in logs
   - Intent data validation
   - Hardcoded paths vs dynamic resolution

6. **Project Conventions**
   - SetupActions callback pattern (not direct Activity refs)
   - ViewModelProvider.Factory pattern
   - AtomicInteger for execution IDs
   - Error messages: user-friendly, actionable

**Key heuristic**: The review should focus on issues that would cause runtime failures on the tablet. This is not a desktop Android app -- it relies on cross-app IPC with Termux, runs shell scripts in proot, and handles long-running background operations. Normal Android lint rules miss these concerns.

---

#### Skill 2: `shell-validator` (Shell Script Validation)

**Purpose**: Validate bash scripts for correctness, proot compatibility, and idempotency.

**When to trigger**: When shell scripts in `assets/scripts/` are created or modified.

**Directory structure**:
```
.claude/skills/shell-validator/
├── SKILL.md
└── proot-constraints.md     # Known proot limitations
```

**What it should check**:

1. **Bash Correctness**
   - `set -euo pipefail` present at top
   - Proper quoting of variables
   - Correct use of `[[ ]]` vs `[ ]`
   - No bashisms that fail under dash/sh (though proot uses bash)

2. **Proot Compatibility**
   - No FUSE-dependent operations (AppImage, Snap, Flatpak)
   - No systemd commands (systemctl, journalctl)
   - No kernel module operations (modprobe, insmod)
   - No mount operations that require root
   - Unix socket operations may fail across proot boundary

3. **Idempotency**
   - Can the script be safely re-run? Check for:
     - `mkdir -p` instead of `mkdir`
     - Existence checks before install operations
     - `wget -O` (overwrite) instead of failing on existing files
     - `apt-get install -y` (non-interactive)

4. **Error Handling**
   - Each major section wrapped with informative error messages
   - Retry logic for network operations
   - Disk space pre-checks for large installs

5. **Security**
   - No credentials or tokens in scripts
   - No `curl | bash` patterns
   - HTTPS URLs only
   - Package integrity verification where possible

**Key context the skill needs**: The scripts run inside Termux's proot-distro environment, which is a chroot-based Linux sandbox on ARM64 Android. It is NOT a full VM -- it has no kernel access, no systemd, and limited /proc. PulseAudio over TCP (not Unix socket) is required for audio, and DISPLAY=:0 is set for X11.

---

#### Skill 3: `security-audit` (Security Audit)

**Purpose**: Deep security review focused on this app's unique attack surface.

**When to trigger**: Before releases, after adding new IPC mechanisms, or when modifying manifest/permissions.

**What it should check**:

1. **Manifest Audit**
   - All components have explicit `exported` attribute
   - Permissions are minimal and justified
   - `<queries>` block matches actual package queries
   - No unnecessary `<intent-filter>` elements

2. **Intent Security**
   - All received intents validate data before use
   - PendingIntent flags are correct per API level
   - No implicit intents for sensitive operations

3. **Token/Credential Handling** (Slice 2+)
   - Tokens stored in EncryptedSharedPreferences only
   - Tokens never logged
   - Tokens passed via environment variables, not CLI args
   - Token cleanup on sign-out

4. **APK Installation Security**
   - APK downloads verified by size/hash
   - Downloaded APKs not accessible to other apps
   - Session cleanup prevents stale sessions

5. **Cross-App IPC (Termux)**
   - RUN_COMMAND permission properly declared
   - Commands use absolute paths
   - No user input passed directly to shell commands
   - Result service not exported

---

#### Skill 4: `testing` (Test Generator)

**Purpose**: Generate appropriate tests for new or modified code.

**When to trigger**: When asked to write tests, or after completing a feature.

**What it should generate**:

1. **Unit Tests** (for business logic)
   - `SetupOrchestrator` step sequencing
   - `ApkDownloader` JSON parsing
   - `TermuxCommandRunner` result handling
   - Error path coverage

2. **Compose UI Tests** (for UI components)
   - `StepItem` rendering for each `StepStatus`
   - `SetupScreen` button enable/disable states
   - `PermissionsBottomSheet` display and actions

3. **Integration-style Tests** (mocking boundaries)
   - Orchestrator with mocked Termux/Installer
   - ViewModel with mocked Orchestrator

**Key context**: This project has no Hilt/Koin, so tests use manual construction with fakes. The Termux and PackageInstaller APIs cannot be tested on a regular emulator -- they need fakes.

**Test patterns for this project**:

```kotlin
// Fake for TermuxCommandRunner
class FakeTermuxCommandRunner : TermuxCommandRunner {
    var nextResult = TermuxResult(stdout = "ok", stderr = null, exitCode = 0, error = null)

    override suspend fun execute(...): TermuxResult = nextResult
}

// Test with StandardTestDispatcher
@Test
fun `setup completes all steps`() = runTest {
    val fakeRunner = FakeTermuxCommandRunner()
    val orchestrator = SetupOrchestrator(
        context = fakeContext,
        termuxHelper = FakeTermuxPackageHelper(installed = true),
        apkDownloader = FakeApkDownloader(),
        apkInstaller = FakeApkInstaller(),
        commandRunner = fakeRunner,
        scriptManager = FakeScriptManager()
    )

    orchestrator.runSetup()

    assertTrue(orchestrator.steps.value.all { it.status is StepStatus.Completed })
}
```

---

#### Skill 5: `pre-commit` (Pre-Commit Check)

**Purpose**: Quick validation before committing changes.

**When to trigger**: Before every commit.

**Checklist**:
```
1. [ ] No hardcoded paths outside companion objects
2. [ ] No CancellationException swallowing in catch blocks
3. [ ] All CompletableDeferred.await() calls have timeouts
4. [ ] Shell scripts have set -euo pipefail
5. [ ] No secrets/tokens in committed files
6. [ ] Manifest changes reviewed for export status
7. [ ] New dependencies justified and pinned to specific versions
```

This should be a lightweight skill that runs fast, not a deep audit. It catches the most common mistakes.

---

#### Skill 6: `slice-planning` (Slice Planning)

**Purpose**: Plan and design new implementation slices.

**When to trigger**: When starting a new slice (Slice 2, 3, etc.).

**Workflow**:
1. Read current state from `_state.md` and relevant feature JSON files
2. Review the MVP plan at `.claude/plans/2026-02-22-mvp-implementation-plan.md`
3. Ask clarifying questions about the slice scope
4. Research any new technical areas (invokes brainstorming skill)
5. Produce a design document following the Slice 1 format
6. Get approval before implementation

This extends the existing `brainstorming` skill with slice-specific context about the project's phased approach.

---

#### Skill 7: `bug-investigation` (Bug Investigation)

**Purpose**: Systematic debugging workflow.

**When to trigger**: When a bug is reported or discovered.

**Workflow**:
1. **Capture**: What happened? What was expected? What device/OS?
2. **Reproduce**: Identify minimal steps
3. **Locate**: Search codebase for relevant code paths
4. **Diagnose**: Identify root cause category:
   - Coroutine lifecycle issue
   - Termux IPC failure
   - PackageInstaller edge case
   - Shell script error
   - UI state inconsistency
5. **Fix**: Apply minimal fix following project conventions
6. **Verify**: How to test the fix
7. **Prevent**: Log defect in `.claude/defects/` for pattern learning

---

### Skills NOT Recommended

**Dependency Update Agent**: For a 15-file project with minimal dependencies, this is overkill. Dependabot or manual `./gradlew dependencyUpdates` suffices.

**Build/CI Agent**: No CI pipeline exists yet. When you set up GitHub Actions (Slice 5), a simple workflow file is better than an agent. Agents add value for complex, contextual decisions -- not running `./gradlew build`.

**Documentation Agent**: The project already has excellent documentation in `.claude/plans/`. Auto-generated docs tend to be worse than hand-written ones. The session management skills (resume-session, end-session) handle documentation well enough.

**Performance Profiling Agent**: This would be valuable for a complex app, but with a single screen and no scrolling lists, there's nothing to profile yet. Add this when the app has multiple screens (Slice 4+).

### Agent Design Principles for This Project

Based on research into effective Claude Code agents:

1. **Keep SKILL.md under 300 lines for task skills, under 500 for reference skills.** The project's brainstorming skill at ~89 lines is a good model.

2. **Use progressive disclosure.** Put the core rules in SKILL.md, put Android reference patterns in separate `.md` files that get loaded only when needed.

3. **Include project-specific examples, not generic ones.** Instead of "use viewModelScope", show the actual `SetupViewModel` pattern from this codebase.

4. **Test with real scenarios.** Before finalizing any skill, run it against actual code changes from Slice 1 to verify it catches real issues.

5. **Prefer skills over CLAUDE.md rules.** CLAUDE.md is loaded every conversation. Skills load on demand. Put broad project rules in CLAUDE.md, put specialized checks in skills.

6. **One skill, one job.** Don't combine code review + testing + security into one mega-skill. They have different triggers and different contexts.

7. **Naming**: Use gerund form (`code-reviewing`, `shell-validating`) or imperative form (`code-review`, `shell-validator`). Be consistent. The project already uses noun/gerund form (`brainstorming`).

---

## Summary of Priority Actions

### Immediate (Before Slice 2)

| Priority | Action | Impact |
|----------|--------|--------|
| HIGH | Fix CancellationException swallowing in SetupOrchestrator catch blocks | Prevents zombie coroutines |
| HIGH | Add timeout to ApkInstaller.install() deferred.await() | Prevents hanging on failed broadcasts |
| MEDIUM | Add abandoned session cleanup in Application.onCreate() | Prevents storage leak |
| MEDIUM | Add retry interceptor to OkHttpClient for GitHub API | Handles transient failures |
| LOW | Add User-Agent header to OkHttpClient | GitHub API best practice |
| LOW | Clean up APK cache after successful install | Storage hygiene |

### For Slice 2 (Auth)

| Priority | Action |
|----------|--------|
| HIGH | Use AppAuth library for OAuth2 PKCE flow |
| HIGH | Store tokens in EncryptedSharedPreferences |
| HIGH | Create network_security_config.xml |
| MEDIUM | Pass tokens via env vars to proot, not CLI args |

### Skills to Create (In Order)

1. `code-review` -- highest value, catches the most bugs
2. `pre-commit` -- lightweight gate before commits
3. `shell-validator` -- critical for Slice 2+ script changes
4. `testing` -- when you start adding tests
5. `bug-investigation` -- when bugs start appearing
6. `slice-planning` -- before Slice 2 begins
7. `security-audit` -- before Slice 5 (distribution)

---

## Sources

### Technology Best Practices
- [Kotlin Coroutines on Android (Android Developers)](https://developer.android.com/kotlin/coroutines)
- [Best Practices for Coroutines in Android](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Advanced Exception Handling in Kotlin Coroutines (ProAndroidDev)](https://proandroiddev.com/advanced-exception-handling-in-kotlin-coroutines-a-guide-for-android-developers-e1aede099252)
- [Jetpack Compose Performance Best Practices (Android Developers)](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Compose Performance (GitHub/skydoves)](https://github.com/skydoves/compose-performance)
- [PackageInstaller.Session (Android Developers)](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session)
- [Painless Android Package Installer (Medium)](https://medium.com/@solrudev/painless-building-of-an-android-package-installer-app-d5a09b5df432)
- [OkHttp Documentation](https://square.github.io/okhttp/)
- [OkHttp HTTPS / Certificate Pinning](https://square.github.io/okhttp/features/https/)
- [Termux RUN_COMMAND Intent Wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [How to Write Idempotent Bash Scripts](https://arslan.io/2019/07/03/how-to-write-idempotent-bash-scripts/)
- [Shell Scripting Best Practices for Production Systems](https://oneuptime.com/blog/post/2026-02-13-shell-scripting-best-practices/view)
- [AppAuth for Android (OpenID)](https://github.com/openid/AppAuth-Android)
- [OAuth 2.0 Best Practices for Native Apps (Auth0)](https://auth0.com/blog/oauth-2-best-practices-for-native-apps/)
- [Android Security Checklist (Android Developers)](https://developer.android.com/topic/security/best-practices.html)
- [Android Intent Security Best Practices](https://securecodingpractices.com/android-intent-security-best-practices-filters-permissions-validation/)
- [OkHttp Retry / Exponential Backoff (Medium)](https://medium.com/@dharmakshetri/exponential-back-off-in-android-753730d4faeb)

### Agent & Skill Design
- [Skill Authoring Best Practices (Anthropic)](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices)
- [Extend Claude with Skills (Claude Code Docs)](https://code.claude.com/docs/en/skills)
- [Claude Agent Skills: A First Principles Deep Dive](https://leehanchung.github.io/blogs/2025/10/26/claude-skills-deep-dive/)
- [Awesome Android Agent Skills (GitHub)](https://github.com/new-silvermoon/awesome-android-agent-skills)
- [Claude Android Ninja (GitHub)](https://github.com/Drjacky/claude-android-ninja)
- [Awesome Claude Skills (VoltAgent)](https://github.com/VoltAgent/awesome-agent-skills)
- [Inside Claude Code Skills: Structure, Prompts, Invocation](https://mikhail.io/2025/10/claude-code-skills/)
- [Anthropic Skills Repository](https://github.com/anthropics/skills)
- [Auto-Reviewing Claude's Code (O'Reilly)](https://www.oreilly.com/radar/auto-reviewing-claudes-code/)

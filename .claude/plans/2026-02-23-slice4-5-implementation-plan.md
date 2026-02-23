# RuneLite for Tablet — Slice 4+5 Implementation Plan

**Date**: 2026-02-23
**Status**: Draft (pending approval)
**Scope**: Slice 4 "Stays Up to Date" + Slice 5 "It's an App" combined

**Confirmed decisions**:
- **Update approach**: Shell script (`update-runelite.sh`) checks GitHub API pre-launch, auto-downloads if newer
- **Update timing**: Pre-launch only (not background). Non-blocking if offline.
- **Polish scope**: All four items — wire SettingsScreen, error log viewer, health checks, main screen polish
- **Display settings**: Resolution mode, fullscreen, keyboard bar — stored in SharedPreferences, sent via Termux:X11 broadcast

---

## User Journey

**Returning user, everything up to date**:
1. Open app -> Launch screen shown instantly (credentials loaded from SharedPreferences)
2. Tap Launch -> "Checking for updates..." (1-2s) -> "Launching..." -> Termux:X11 opens with RuneLite
3. RuneLite is already on the latest version

**Returning user, RuneLite update available**:
1. Open app -> Launch screen
2. Tap Launch -> "Checking for updates..." -> "Updating RuneLite..." (10-30s) -> "Launching..." -> Termux:X11 opens
3. RuneLite is now updated, user never had to do anything

**Returning user, environment broken (e.g., Termux data cleared)**:
1. Open app -> Launch screen
2. Tap Launch -> health check runs -> dialog: "Java is not installed. Run setup again?"
3. Tap "Run Setup" -> setup wizard opens at the failed step
4. Setup re-runs only the broken steps -> back to Launch screen

**Launch failure**:
1. Tap Launch -> launch script fails
2. "Launch failed" message with "View Logs" button
3. Log viewer shows the session log with copy/share buttons for troubleshooting

---

## Slice 4: Stays Up to Date

### What's Already Done

| Component | Status | Notes |
|---|---|---|
| `download-runelite.sh` | **DONE** | Downloads RuneLite.jar, verifies size + magic bytes. Does NOT store version. |
| GitHub API access | **DONE** | Script already hits `releases/latest/download` redirect. |
| `commandRunner.execute()` | **DONE** | Can run any shell script and capture output. |
| OkHttp | **DONE** | Available in ViewModel if needed (not used for update). |

### Architecture

#### `update-runelite.sh` — New Shell Script

**Location**: `assets/scripts/update-runelite.sh`

**Logic**:
```
1. Read stored version from ~/.runelite-tablet/markers/runelite-version (or "none")
2. curl/wget GitHub API: https://api.github.com/repos/runelite/launcher/releases/latest
   - Parse "tag_name" from JSON response (e.g., "2.7.6")
   - Timeout: 10 seconds
3. If API fails (offline, timeout, rate limit):
   - Print "UPDATE_STATUS offline"
   - Exit 0 (non-blocking — launch with current version)
4. If stored version == latest version:
   - Print "UPDATE_STATUS current <version>"
   - Exit 0
5. If new version available:
   - Print "UPDATE_STATUS downloading <old_version> -> <new_version>"
   - Download RuneLite.jar to proot home (reuse download logic from download-runelite.sh)
   - Verify file: size > 1MB, ZIP magic bytes
   - If verification fails:
     - Print "UPDATE_STATUS failed"
     - Keep old jar (don't delete a working version)
     - Exit 0 (launch with old version)
   - Write new version to runelite-version marker
   - Print "UPDATE_STATUS updated <new_version>"
   - Exit 0
```

**Output protocol** (parsed by Kotlin):
```
UPDATE_STATUS offline           # Network unavailable, launching with current
UPDATE_STATUS current 2.7.6    # Already on latest
UPDATE_STATUS downloading 2.7.5 -> 2.7.6  # Downloading new version
UPDATE_STATUS updated 2.7.6    # Successfully updated
UPDATE_STATUS failed            # Download/verification failed, keeping old
```

**Shell script patterns**:
- Same shebang as other scripts: `#!/data/data/com.termux/files/usr/bin/bash`
- `set -euo pipefail` in outer shell
- proot calls wrapped with `|| true` then positive verification (existing pattern)
- JSON parsing: `grep -o '"tag_name":"[^"]*"' | cut -d'"' -f4` (no jq dependency)
- Download runs inside proot (where wget is available and jar lives)

#### Modify `download-runelite.sh`

Add version storage after successful download:
```bash
# After writing step-runelite.done marker, also store version
# Parse version from the URL or GitHub API response
echo "unknown" > "$MARKER_DIR/runelite-version"
```

The initial version is "unknown" because `download-runelite.sh` uses the `/latest/download` redirect and doesn't parse the version tag. The first `update-runelite.sh` run will discover the actual version and write it.

#### `LaunchState` — New Sealed Class

```kotlin
sealed class LaunchState {
    object Idle : LaunchState()
    object CheckingUpdate : LaunchState()
    data class Updating(val fromVersion: String, val toVersion: String) : LaunchState()
    object CheckingHealth : LaunchState()
    object RefreshingTokens : LaunchState()
    object Launching : LaunchState()
    data class Failed(val message: String) : LaunchState()
}
```

Exposed via `StateFlow<LaunchState>` from `SetupViewModel`. LaunchScreen observes this to show progress.

#### Modified `launchRuneLite()` Flow

```kotlin
fun launchRuneLite() {
    viewModelScope.launch {
        _launchState.value = LaunchState.CheckingUpdate

        // 1. Update check (non-blocking on failure)
        val updateResult = runUpdateCheck()  // 60s timeout
        when {
            updateResult.contains("downloading") -> {
                val parts = parseUpdateParts(updateResult)
                _launchState.value = LaunchState.Updating(parts.from, parts.to)
                // Script handles download internally — wait for completion
            }
        }

        // 2. Health check
        _launchState.value = LaunchState.CheckingHealth
        val healthResult = runHealthCheck()  // 5s timeout
        if (healthResult is HealthCheckResult.Degraded) {
            _launchState.value = LaunchState.Failed("Setup incomplete: ${healthResult.failures.joinToString()}")
            _showHealthDialog.value = healthResult.failures
            return@launch
        }

        // 3. Token refresh (existing logic)
        if (credentialManager.hasCredentials()) {
            _launchState.value = LaunchState.RefreshingTokens
            when (val result = refreshIfNeeded()) {
                is AuthResult.NeedsLogin -> {
                    _launchState.value = LaunchState.Idle
                    _currentScreen.value = AppScreen.Login
                    return@launch
                }
                is AuthResult.NetworkError -> {
                    AppLog.w("AUTH", "refresh failed, continuing with existing tokens")
                }
                else -> {}
            }
        }

        // 4. Launch
        _launchState.value = LaunchState.Launching
        // ... existing env file + launch() logic ...

        _launchState.value = LaunchState.Idle
    }
}
```

### Tasks

#### 4.1 — `update-runelite.sh` (NEW)

Write the shell script with:
- GitHub API version check via `wget` (available in proot) with 10s timeout
- JSON parsing without jq
- Download into proot home, verify size + magic bytes
- Version marker read/write
- Structured output protocol
- Idempotent, non-destructive (never deletes working jar)
- All proot calls wrapped with `|| true` then positive verification

#### 4.2 — Modify `download-runelite.sh`

- After writing `step-runelite.done`, also write `"unknown"` to `~/.runelite-tablet/markers/runelite-version`
- This ensures the version file exists even for initial setup (update script will correct it on first run)

#### 4.3 — Add `LaunchState` to SetupViewModel

- New `LaunchState` sealed class (Idle, CheckingUpdate, Updating, CheckingHealth, RefreshingTokens, Launching, Failed)
- New `_launchState: MutableStateFlow<LaunchState>` + public `launchState: StateFlow<LaunchState>`
- New `suspend fun runUpdateCheck(): String` — runs `update-runelite.sh` via `commandRunner.execute()`, 60s timeout, returns stdout
- Parse update status from script output

#### 4.4 — Wire update into `launchRuneLite()`

- Insert update check step at the top of `launchRuneLite()` (before token refresh)
- Set `_launchState` at each stage
- On update timeout/failure: log warning, proceed with current version (non-blocking)

#### 4.5 — Update LaunchScreen UI

- Observe `viewModel.launchState.collectAsState()`
- When `Idle`: show Launch button (current behavior)
- When `CheckingUpdate`: show "Checking for updates..." with progress indicator, Launch button disabled
- When `Updating`: show "Updating RuneLite (2.7.5 -> 2.7.6)..." with progress indicator
- When `CheckingHealth`: show "Verifying environment..."
- When `Launching`: show "Launching RuneLite..." with progress indicator
- When `Failed`: show error message with "View Logs" button + "Try Again" button

### Exit Criteria

- [ ] Launch with internet: update check runs, "Checking for updates..." shown briefly
- [ ] Launch with newer version available: RuneLite.jar updated silently, correct version shown after
- [ ] Launch without internet: update check times out gracefully, launches with current version
- [ ] Launch with already-current version: "Already up to date", launches immediately
- [ ] Version stored in marker file after initial setup + after each update
- [ ] No crash or hang if GitHub API is unreachable, rate-limited, or returns unexpected response

---

## Slice 5: It's an App (Polish)

### What's Already Done

| Component | Status | Notes |
|---|---|---|
| `SettingsScreen.kt` | **SCAFFOLD** | Account/Setup/About sections built, not wired into navigation |
| `AppLog` system | **DONE** | Writes structured logs to `files/logs/rlt-session-*.log` |
| LaunchScreen | **BASIC** | Shows display name + Launch button + unconnected Settings button |
| `AppScreen` sealed class | **DONE** | Has Setup, Login, CharacterSelect, Launch, AuthError |
| Health check markers | **PARTIAL** | `check-markers.sh` checks step markers, not runtime health |

### 5A: Wire SettingsScreen Navigation

#### Changes

**`SetupViewModel.kt`**:
```kotlin
// Add to AppScreen sealed class
object Settings : AppScreen()
object LogViewer : AppScreen()

// New methods
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
```

**`SetupScreen.kt`** — add router cases:
```kotlin
is AppScreen.Settings -> SettingsScreen(
    displayName = viewModel.credentialManager.getCredentials()?.displayName,
    appVersion = BuildConfig.VERSION_NAME,
    runeliteVersion = runeliteVersion,  // from LaunchState or separate read
    onSignOut = { viewModel.signOut() },
    onResetSetup = { viewModel.resetSetup() },
    onViewLogs = { viewModel.navigateToLogViewer() },
    onBack = { viewModel.navigateBackToLaunch() }
)
is AppScreen.LogViewer -> LogViewerScreen(
    onBack = { viewModel.navigateBackToLaunch() }
)
```

**LaunchScreen** — wire Settings button:
```kotlin
IconButton(onClick = { viewModel.navigateToSettings() }) {
    Icon(Icons.Default.Settings, contentDescription = "Settings")
}
```

### 5B: Display Settings

#### `DisplayPreferences.kt` (NEW)

```kotlin
class DisplayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("display_prefs", Context.MODE_PRIVATE)

    var resolutionMode: String
        get() = prefs.getString("resolution_mode", "native") ?: "native"
        set(value) = prefs.edit().putString("resolution_mode", value).apply()

    var customWidth: Int
        get() = prefs.getInt("custom_width", 2960)
        set(value) = prefs.edit().putInt("custom_width", value).apply()

    var customHeight: Int
        get() = prefs.getInt("custom_height", 1848)
        set(value) = prefs.edit().putInt("custom_height", value).apply()

    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", true)
        set(value) = prefs.edit().putBoolean("fullscreen", value).apply()

    var showKeyboardBar: Boolean
        get() = prefs.getBoolean("show_keyboard_bar", false)
        set(value) = prefs.edit().putBoolean("show_keyboard_bar", value).apply()
}
```

#### Display Settings UI

Add to `SettingsScreen.kt` — new "Display" section between Account and Setup:

```kotlin
// Display section
Text("Display", style = MaterialTheme.typography.titleMedium)

// Resolution mode — radio group
RadioButtonRow("Native", selected = resMode == "native", onClick = { setResMode("native") })
RadioButtonRow("Scaled", selected = resMode == "scaled", onClick = { setResMode("scaled") })
RadioButtonRow("Custom", selected = resMode == "exact", onClick = { setResMode("exact") })

// Custom width/height fields (visible only when "Custom" selected)
if (resMode == "exact") {
    OutlinedTextField(value = customWidth, onValueChange = { ... }, label = { Text("Width") })
    OutlinedTextField(value = customHeight, onValueChange = { ... }, label = { Text("Height") })
}

// Fullscreen toggle
SwitchRow("Fullscreen", checked = fullscreen, onCheckedChange = { ... })

// Keyboard bar toggle
SwitchRow("Show keyboard bar", checked = showKeyboardBar, onCheckedChange = { ... })
```

#### Wire into Launch

Modify `launch()` in `SetupViewModel.kt` to read from `DisplayPreferences`:

```kotlin
private fun launch(envFilePath: String? = null) {
    val displayPrefs = displayPreferences  // injected via constructor

    val prefIntent = Intent("com.termux.x11.CHANGE_PREFERENCE").apply {
        setPackage("com.termux.x11")
        putExtra("fullscreen", displayPrefs.fullscreen.toString())
        putExtra("showAdditionalKbd", displayPrefs.showKeyboardBar.toString())
        putExtra("displayResolutionMode", displayPrefs.resolutionMode)
        if (displayPrefs.resolutionMode == "exact") {
            putExtra("displayResolutionExactX", displayPrefs.customWidth.toString())
            putExtra("displayResolutionExactY", displayPrefs.customHeight.toString())
        }
    }
    context.sendBroadcast(prefIntent)
    // ... rest of launch logic unchanged
}
```

### 5C: Health Checks

#### `health-check.sh` (NEW)

**Location**: `assets/scripts/health-check.sh`

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# health-check.sh — Verify proot/Java/RuneLite integrity before launch.
# Always exits 0. Health status reported via structured output.

ROOTFS_DIR="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
RUNELITE_JAR="/root/runelite/RuneLite.jar"

# Check proot/Ubuntu rootfs
if [ -d "$ROOTFS_DIR" ]; then
    echo "HEALTH proot OK"
else
    echo "HEALTH proot FAIL missing_rootfs"
fi

# Check Java binary inside proot
JAVA_BIN=$(proot-distro login ubuntu -- which java 2>/dev/null || echo "")
if [ -n "$JAVA_BIN" ]; then
    echo "HEALTH java OK"
else
    echo "HEALTH java FAIL binary_not_found"
fi

# Check RuneLite.jar exists and is valid
JAR_CHECK=$(proot-distro login ubuntu -- bash -c "
    if [ -f '$RUNELITE_JAR' ]; then
        SIZE=\$(wc -c < '$RUNELITE_JAR' 2>/dev/null || echo 0)
        if [ \"\$SIZE\" -gt 1048576 ]; then
            echo 'OK'
        else
            echo 'FAIL jar_too_small'
        fi
    else
        echo 'FAIL jar_missing'
    fi
" 2>/dev/null || echo "FAIL proot_error")

echo "HEALTH runelite $JAR_CHECK"

echo "=== health-check.sh complete ==="
```

#### Kotlin Integration

**`HealthCheckResult` sealed class** (in `SetupViewModel.kt` or separate file):
```kotlin
sealed class HealthCheckResult {
    object Healthy : HealthCheckResult()
    data class Degraded(val failures: List<String>) : HealthCheckResult()
    object Inconclusive : HealthCheckResult()  // Termux not running, timeout
}
```

**`runHealthCheck()` suspend function**:
```kotlin
private suspend fun runHealthCheck(): HealthCheckResult {
    return try {
        val result = withTimeout(10_000L) {
            commandRunner.execute("health-check.sh")
        }
        if (!result.isSuccess) return HealthCheckResult.Inconclusive

        val failures = result.stdout.lines()
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
        HealthCheckResult.Inconclusive
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("HEALTH", "Health check failed: ${e.message}")
        HealthCheckResult.Inconclusive
    }
}
```

**Health dialog**: When `Degraded`, show `AlertDialog`:
```
Environment Issue Detected

The following components need repair:
- proot: missing rootfs
- java: binary not found

[Run Setup Again]  [Launch Anyway]
```

"Run Setup Again" → clear affected step markers in `stateStore`, navigate to Setup screen.
"Launch Anyway" → proceed (may fail, but user's choice).

### 5D: Error Log Viewer

#### `LogViewerScreen.kt` (NEW, `ui/screens/`)

```kotlin
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logContent by remember { mutableStateOf("Loading...") }

    LaunchedEffect(Unit) {
        logContent = withContext(Dispatchers.IO) {
            val logDir = File(context.filesDir, "logs")
            val latestLog = logDir.listFiles()
                ?.filter { it.name.startsWith("rlt-session-") && it.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            latestLog?.readText() ?: "No logs found"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Copy button
                    IconButton(onClick = { copyToClipboard(context, logContent) }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                    // Share button
                    IconButton(onClick = { shareLog(context, logContent) }) {
                        Icon(Icons.Default.Share, "Share")
                    }
                }
            )
        }
    ) { padding ->
        SelectionContainer {
            Text(
                text = logContent,
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}
```

**Copy/Share helpers**:
```kotlin
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("RuneLite Tablet Log", text))
}

private fun shareLog(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "RuneLite Tablet Session Log")
    }
    context.startActivity(Intent.createChooser(intent, "Share log via"))
}
```

### 5E: Launch Screen Polish

**Status indicators** added to LaunchScreen:

```kotlin
@Composable
fun LaunchScreen(
    displayName: String?,
    runeliteVersion: String?,
    healthStatus: HealthCheckResult?,
    launchState: LaunchState,
    onLaunch: () -> Unit,
    onSettings: () -> Unit,
    onViewLogs: () -> Unit
) {
    Column {
        // Status card
        Card {
            // Account row
            Row {
                Icon(Icons.Default.Person)
                Text(displayName ?: "Not signed in")
                // Session indicator
                if (displayName != null) {
                    Text("Session active", color = MaterialTheme.colorScheme.primary)
                }
            }

            // RuneLite version row
            Row {
                Icon(Icons.Default.Info)
                Text("RuneLite ${runeliteVersion ?: "unknown"}")
            }

            // Environment health row
            Row {
                val (color, text) = when (healthStatus) {
                    is HealthCheckResult.Healthy -> Color.Green to "Environment OK"
                    is HealthCheckResult.Degraded -> Color.Red to "Issues detected"
                    is HealthCheckResult.Inconclusive -> Color.Yellow to "Not checked"
                    null -> Color.Gray to "Not checked"
                }
                Icon(Icons.Default.Circle, tint = color)
                Text(text)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Launch button or progress
        when (val state = launchState) {
            is LaunchState.Idle -> {
                Button(onClick = onLaunch, modifier = Modifier.fillMaxWidth()) {
                    Text("Launch RuneLite")
                }
            }
            is LaunchState.CheckingUpdate -> LaunchProgress("Checking for updates...")
            is LaunchState.Updating -> LaunchProgress("Updating RuneLite (${state.fromVersion} -> ${state.toVersion})...")
            is LaunchState.CheckingHealth -> LaunchProgress("Verifying environment...")
            is LaunchState.RefreshingTokens -> LaunchProgress("Refreshing session...")
            is LaunchState.Launching -> LaunchProgress("Launching RuneLite...")
            is LaunchState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Row {
                    OutlinedButton(onClick = onViewLogs) { Text("View Logs") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onLaunch) { Text("Try Again") }
                }
            }
        }
    }
}
```

---

## Implementation Order

### Slice 4 (Update Manager) — Foundation

| Phase | Task | Files | Depends On |
|-------|------|-------|------------|
| 4.1 | Write `update-runelite.sh` | `assets/scripts/update-runelite.sh` (NEW) | — |
| 4.2 | Modify `download-runelite.sh` to write version marker | `assets/scripts/download-runelite.sh` (MOD) | — |
| 4.3 | Add `LaunchState` sealed class + StateFlow | `SetupViewModel.kt` (MOD) | — |
| 4.4 | Wire update check into `launchRuneLite()` | `SetupViewModel.kt` (MOD) | 4.1, 4.3 |
| 4.5 | Update LaunchScreen UI for launch progress | `SetupScreen.kt` (MOD) | 4.3 |

### Slice 5 (Polish) — Build on Top

| Phase | Task | Files | Depends On |
|-------|------|-------|------------|
| 5.1 | Wire SettingsScreen navigation | `SetupViewModel.kt` (MOD), `SetupScreen.kt` (MOD) | — |
| 5.2 | Create `DisplayPreferences.kt` | `ui/DisplayPreferences.kt` (NEW) | — |
| 5.3 | Add display settings UI to SettingsScreen | `SettingsScreen.kt` (MOD) | 5.2 |
| 5.4 | Wire display prefs into `launch()` | `SetupViewModel.kt` (MOD) | 5.2 |
| 5.5 | Write `health-check.sh` | `assets/scripts/health-check.sh` (NEW) | — |
| 5.6 | Health check integration (pre-launch + dialog) | `SetupViewModel.kt` (MOD), `SetupScreen.kt` (MOD) | 5.5 |
| 5.7 | Create `LogViewerScreen.kt` | `ui/screens/LogViewerScreen.kt` (NEW) | — |
| 5.8 | Wire log viewer navigation | `SetupViewModel.kt` (MOD), `SetupScreen.kt` (MOD), `SettingsScreen.kt` (MOD) | 5.1, 5.7 |
| 5.9 | Launch screen polish (status indicators) | `SetupScreen.kt` (MOD) | 4.3, 4.5 |

### Parallelization Opportunities

These phases can run in parallel:
- **Group A** (shell scripts): 4.1, 4.2, 5.5 — no Kotlin dependencies
- **Group B** (Kotlin infra): 4.3, 5.2 — independent new code
- **Group C** (UI): 5.7 — standalone Compose screen

Sequential dependencies:
- 4.4 needs 4.1 + 4.3
- 4.5 needs 4.3
- 5.3 needs 5.2
- 5.4 needs 5.2
- 5.6 needs 5.5
- 5.8 needs 5.1 + 5.7
- 5.9 needs 4.3 + 4.5

---

## New Files Summary

| File | Purpose |
|------|---------|
| `assets/scripts/update-runelite.sh` | Check GitHub API, download newer RuneLite if available |
| `assets/scripts/health-check.sh` | Verify proot/Java/RuneLite integrity |
| `ui/DisplayPreferences.kt` | SharedPreferences wrapper for display settings |
| `ui/screens/LogViewerScreen.kt` | Scrollable log viewer with copy/share |

## Modified Files Summary

| File | Changes |
|------|---------|
| `assets/scripts/download-runelite.sh` | Write version marker after initial download |
| `SetupViewModel.kt` | LaunchState flow, update check, health check, settings/log navigation, display prefs |
| `SetupScreen.kt` | Router cases for Settings/LogViewer, launch progress UI, status indicators |
| `SettingsScreen.kt` | Display settings section, "View Logs" row, wire callbacks |
| `build.gradle.kts` | (if needed) Material Icons Extended for new icons |

---

## Error Handling

| Scenario | Behavior |
|---|---|
| GitHub API unreachable (offline) | Log warning, launch with current RuneLite version |
| GitHub API rate limit (403) | Same as offline — non-blocking |
| Update download fails mid-download | Keep old jar, log error, launch with current version |
| Update download corrupted (bad magic bytes) | Delete bad download, keep old jar, launch with current |
| Health check: proot missing | Dialog: "proot not installed. Run setup again?" |
| Health check: Java missing | Dialog: "Java not installed. Run setup again?" |
| Health check: RuneLite.jar missing | Dialog: "RuneLite not found. Run setup again?" |
| Health check: Termux not running | Inconclusive — skip health check, proceed to launch |
| Health check timeout (>10s) | Inconclusive — skip, proceed to launch |
| Launch script failure | Show "Launch failed" + "View Logs" button |
| Log file not found | LogViewer shows "No logs found" |

All errors are recoverable without app restart. No error puts the app in an unrecoverable state.

---

## Exit Criteria

### Slice 4

- [ ] Launch with internet: update check runs, brief progress shown
- [ ] Newer version available: RuneLite.jar updated silently before launch
- [ ] No internet: update check fails gracefully, launches with current version
- [ ] Already up to date: skips download, launches immediately
- [ ] Version marker file written after initial setup and after each update
- [ ] No crash on unexpected GitHub API response (rate limit, 500, malformed JSON)

### Slice 5

- [ ] Settings screen reachable from Launch screen via Settings button
- [ ] Sign Out clears credentials, shows Login screen
- [ ] Reset Setup clears all state, shows Setup wizard from step 1
- [ ] Display settings (resolution mode, fullscreen, keyboard bar) persist and apply on launch
- [ ] Custom resolution fields shown only when "Custom" mode selected
- [ ] Health check runs before launch, shows dialog if environment degraded
- [ ] "Run Setup Again" from health dialog opens Setup wizard at the failed step
- [ ] "Launch Anyway" bypasses health check failure
- [ ] Log viewer shows most recent session log in monospace
- [ ] Copy button copies full log to clipboard
- [ ] Share button opens Android share sheet with log text
- [ ] Log viewer accessible from Settings (About section) and launch failure
- [ ] Launch screen shows: display name, RuneLite version, environment health dot
- [ ] Launch progress states visible during launch (checking/updating/launching)
- [ ] Launch failure shows error message + "View Logs" + "Try Again" buttons

---

## Deferred (Not in This Plan)

- Audio passthrough (PulseAudio in proot) — already partially in launch script, but no UI controls
- GPU acceleration (Mesa Zink + Turnip) — research complete, not in MVP scope
- Self-update (updating the Android app itself) — only RuneLite.jar auto-updates
- Distributable release (Play Store, multi-device support)
- Per-step setup progress (e.g., download percentage) — only step-level status
- RuneLite plugin management from the Android app

# Slice 1 — "It Launches" — Detailed Implementation Design

**Date**: 2026-02-22
**Status**: Approved
**Approach**: B — Auto-Install Everything
**Parent Plan**: `.claude/plans/2026-02-22-mvp-implementation-plan.md`

## Overview

Slice 1 gets RuneLite running on the tablet through a setup wizard that auto-installs Termux and Termux:X11, configures permissions (one manual step), installs the Linux environment + Java + RuneLite via shell scripts, and provides a Launch button.

**Exit Criteria**: Install APK → setup runs all 7 steps → tap Launch → RuneLite appears on screen via Termux:X11.

---

## Project Structure

```
runelite-tablet/
├── app/
│   ├── src/main/
│   │   ├── java/com/runelitetablet/
│   │   │   ├── RuneLiteTabletApp.kt          # Application class
│   │   │   ├── MainActivity.kt               # Single-activity, hosts Compose nav
│   │   │   ├── ui/
│   │   │   │   ├── theme/
│   │   │   │   │   └── Theme.kt              # Material3 dark theme
│   │   │   │   ├── screens/
│   │   │   │   │   └── SetupScreen.kt        # Setup wizard + launch (single screen)
│   │   │   │   └── components/
│   │   │   │       └── StepItem.kt           # Reusable step row (icon + label + status)
│   │   │   ├── termux/
│   │   │   │   ├── TermuxCommandRunner.kt    # RUN_COMMAND intent builder + sender
│   │   │   │   ├── TermuxResultService.kt    # Service receiving PendingIntent results
│   │   │   │   └── TermuxPackageHelper.kt    # Check installed, version, package queries
│   │   │   ├── installer/
│   │   │   │   ├── ApkDownloader.kt          # Downloads APK from GitHub Releases API
│   │   │   │   ├── ApkInstaller.kt           # PackageInstaller session API wrapper
│   │   │   │   └── InstallResultReceiver.kt  # BroadcastReceiver for install results
│   │   │   └── setup/
│   │   │       ├── SetupStep.kt              # Sealed class defining each step + state
│   │   │       ├── SetupOrchestrator.kt      # Runs steps in sequence, tracks state
│   │   │       ├── SetupViewModel.kt         # ViewModel bridging orchestrator to UI
│   │   │       └── ScriptManager.kt          # Copies scripts from assets to Termux
│   │   ├── assets/
│   │   │   └── scripts/
│   │   │       ├── setup-environment.sh       # proot + Ubuntu + Java + RuneLite
│   │   │       └── launch-runelite.sh         # Enter proot, set DISPLAY, run java
│   │   └── res/
│   │       └── ...
│   └── build.gradle.kts
├── build.gradle.kts                           # Root build file
├── settings.gradle.kts
└── gradle/
```

**~15 Kotlin files** + 2 shell scripts. Single-screen app.

---

## Setup Steps & Orchestration

### Step Definitions

```kotlin
sealed class SetupStep(val id: String, val label: String) {
    object InstallTermux      : SetupStep("termux",      "Install Termux")
    object InstallTermuxX11   : SetupStep("termux_x11",  "Install Termux:X11")
    object EnablePermissions  : SetupStep("permissions",  "Configure Permissions")
    object InstallProot       : SetupStep("proot",        "Install Linux Environment")
    object InstallJava        : SetupStep("java",         "Install Java Runtime")
    object DownloadRuneLite   : SetupStep("runelite",     "Download RuneLite")
    object VerifySetup        : SetupStep("verify",       "Verify Setup")
}
```

### Step Flow

| # | Step | How it works | Completion check |
|---|------|-------------|-----------------|
| 1 | Install Termux | Check if `com.termux` installed. If not: download APK from GitHub Releases API, install via `PackageInstaller` session. | `getPackageInfo("com.termux")` succeeds |
| 2 | Install Termux:X11 | Same flow for `com.termux.x11`. Download from nightly release tag. | `getPackageInfo("com.termux.x11")` succeeds |
| 3 | Configure Permissions | **Manual step.** Show instructions: (a) open Termux, run `echo "allow-external-apps=true" >> ~/.termux/termux.properties`, (b) grant RUN_COMMAND permission to our app, (c) disable battery optimization for Termux. App has "Verify" button that sends a test RUN_COMMAND. | Test RUN_COMMAND returns exit code 0 |
| 4 | Install Linux Env | Deploy and run `setup-environment.sh` via RUN_COMMAND (background mode). Installs proot-distro + Ubuntu ARM64. | Script exits 0 |
| 5 | Install Java | Runs inside setup script: `proot-distro login ubuntu -- apt-get install -y openjdk-11-jdk` | Script exits 0 |
| 6 | Download RuneLite | Runs inside setup script: downloads RuneLite.jar to `/root/runelite/` | Script exits 0 + file exists |
| 7 | Verify Setup | Verification script checks: proot works, java exists, runelite.jar exists, X11 accessible | All checks pass |

**Note**: Steps 4-6 are handled by a single `setup-environment.sh` script. The orchestrator treats the script as one operation but the script has internal stages with echo markers.

### SetupOrchestrator

```kotlin
class SetupOrchestrator(
    private val termuxHelper: TermuxPackageHelper,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val commandRunner: TermuxCommandRunner,
    private val scriptManager: ScriptManager
) {
    val steps: StateFlow<List<StepState>>
    val currentStep: StateFlow<SetupStep?>

    suspend fun runSetup()          // Runs all steps in sequence
    suspend fun retryCurrentStep()  // Re-runs the failed step
    fun skipToStep(step: SetupStep) // For resuming after app restart
}
```

Each step emits one of: `Pending`, `InProgress`, `Completed`, `Failed(message)`, `ManualAction(instructions)`.

On app re-launch, the orchestrator re-evaluates each step's completion check and skips already-done steps.

---

## Termux Integration Layer

### TermuxPackageHelper

```kotlin
class TermuxPackageHelper(private val context: Context) {
    fun isTermuxInstalled(): Boolean
    fun isTermuxX11Installed(): Boolean
    fun getTermuxVersionCode(): Long?
}
```

Requires `<queries>` block in manifest for Android 11+ package visibility.

### TermuxCommandRunner

```kotlin
class TermuxCommandRunner(private val context: Context) {
    suspend fun execute(
        commandPath: String,
        arguments: Array<String>? = null,
        workdir: String? = null,
        background: Boolean = true
    ): TermuxResult

    fun launch(
        commandPath: String,
        arguments: Array<String>? = null,
        sessionAction: Int = SESSION_ACTION_SWITCH_NEW_NO_ACTIVITY
    )
}

data class TermuxResult(
    val stdout: String?,
    val stderr: String?,
    val exitCode: Int,
    val error: String?
) {
    val isSuccess: Boolean get() = exitCode == 0 && error == null
}
```

**Result flow:**

```
App                          Termux                    TermuxResultService
 |                             |                              |
 |-- RUN_COMMAND intent ------>|                              |
 |   (with PendingIntent)      |                              |
 |                             |-- runs command -->            |
 |                             |                              |
 |                             |-- PendingIntent ------------>|
 |                             |   (result bundle)            |
 |<------------- completes CompletableDeferred ---------------|
```

1. `execute()` generates unique `executionId`, registers `CompletableDeferred<TermuxResult>` in a `ConcurrentHashMap`
2. Builds `PendingIntent` targeting `TermuxResultService` with that `executionId`
3. Sends `RUN_COMMAND` intent to Termux
4. Awaits the deferred (with timeout)
5. `TermuxResultService` receives result, looks up deferred by `executionId`, completes it

### TermuxResultService

Modern `Service` with `HandlerThread` (not deprecated `IntentService`):

```kotlin
class TermuxResultService : Service() {
    companion object {
        val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extract executionId and result bundle
        // Look up CompletableDeferred in pendingResults map
        // Complete it with parsed TermuxResult
        // stopSelf(startId)
    }
}
```

### RUN_COMMAND Intent Constants

| Extra | Value |
|-------|-------|
| Action | `com.termux.RUN_COMMAND` |
| Service target | `com.termux/com.termux.app.RunCommandService` |
| Command path | `com.termux.RUN_COMMAND_PATH` |
| Arguments | `com.termux.RUN_COMMAND_ARGUMENTS` |
| Working dir | `com.termux.RUN_COMMAND_WORKDIR` |
| Background | `com.termux.RUN_COMMAND_BACKGROUND` |
| Stdin | `com.termux.RUN_COMMAND_STDIN` |
| Session action | `com.termux.RUN_COMMAND_SESSION_ACTION` |
| Pending intent | `com.termux.RUN_COMMAND_PENDING_INTENT` |

**Session action values** (foreground only):
- `"0"` — Switch to new session, open Termux activity (for RuneLite launch)
- `"2"` — Switch to new session, don't open activity (for background work)

**PendingIntent flags**: `FLAG_ONE_SHOT or FLAG_MUTABLE` (mutable required on Android 12+)

**Result bundle keys**: `stdout`, `stderr`, `exitCode`, `err`, `errmsg`

**Truncation**: stdout + stderr capped at 100KB combined.

### Key Technical Details

- **Background mode** (`EXTRA_BACKGROUND = true`) for setup scripts — clean stdout/stderr separation
- **Foreground mode** with session action `"0"` for RuneLite launch — X11 display handoff
- **Timeouts**: 10 minutes for setup commands, 30 seconds for verification commands
- **Path expansion**: `$PREFIX/` expands to `/data/data/com.termux/files/usr`, `~/` expands to `/data/data/com.termux/files/home`

---

## APK Download & Install Pipeline

### ApkDownloader

```kotlin
class ApkDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    suspend fun download(
        repo: GitHubRepo,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File

    private fun selectAsset(assets: List<ReleaseAsset>): ReleaseAsset
}

enum class GitHubRepo(
    val owner: String,
    val repo: String,
    val releaseTag: String?,
    val assetPattern: Regex
) {
    TERMUX(
        "termux", "termux-app", null,
        Regex("""termux-app_v.+\+apt-android-7-github-debug_arm64-v8a\.apk""")
    ),
    TERMUX_X11(
        "termux", "termux-x11", "nightly",
        Regex("""app-arm64-v8a-debug\.apk""")
    )
}
```

**Flow:**
1. GET `https://api.github.com/repos/{owner}/{repo}/releases/latest` (or `/tags/nightly` for X11)
2. Parse JSON, find asset matching `assetPattern`
3. Download `browser_download_url` to `context.cacheDir/apks/`
4. Report progress via callback
5. Return `File` reference

Hardcoded to `arm64-v8a` for Tab S10 Ultra. Falls back to `universal` if arm64 asset not found.

### ApkInstaller

```kotlin
class ApkInstaller(private val context: Context) {
    suspend fun install(apkFile: File): InstallResult
    fun canInstallPackages(): Boolean
    fun requestInstallPermission(activity: Activity)
}

sealed class InstallResult {
    object Success : InstallResult()
    data class NeedsUserAction(val intent: Intent) : InstallResult()
    data class Failure(val message: String) : InstallResult()
}
```

**Flow:**
1. Check `canInstallPackages()` — if false, launch `ACTION_MANAGE_UNKNOWN_APP_SOURCES` settings
2. Create `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`
3. Open session, write APK bytes via `session.openWrite()`
4. Create `PendingIntent` targeting `InstallResultReceiver`
5. `session.commit()` — triggers system install confirmation dialog
6. User taps "Install" — receiver gets `STATUS_SUCCESS`

---

## Shell Scripts

### Script Deployment

Scripts are bundled in `assets/scripts/` and deployed to Termux via `EXTRA_STDIN`:

```kotlin
class ScriptManager(private val context: Context) {
    suspend fun deployScripts(commandRunner: TermuxCommandRunner)
    fun getScriptPath(name: String): String
}
```

Deployment uses `EXTRA_STDIN` to pipe script content through bash, writing to `~/scripts/`:
```
RUN_COMMAND_PATH: /data/data/com.termux/files/usr/bin/bash
ARGUMENTS: ["-c", "mkdir -p ~/scripts && cat > ~/scripts/setup-environment.sh && chmod +x ~/scripts/setup-environment.sh"]
STDIN: <script content from assets>
```

Scripts persist in Termux's home dir for re-runs and debugging.

### setup-environment.sh

```bash
#!/bin/bash
set -euo pipefail

echo "=== Step 1: Updating Termux packages ==="
pkg update -y
pkg install -y proot-distro wget

echo "=== Step 2: Installing Ubuntu ARM64 ==="
if proot-distro list 2>/dev/null | grep -q "ubuntu"; then
    echo "Ubuntu already installed, skipping"
else
    proot-distro install ubuntu
fi

echo "=== Step 3: Installing Java inside Ubuntu ==="
proot-distro login ubuntu -- bash -c '
    set -euo pipefail
    apt-get update
    apt-get install -y openjdk-11-jdk wget ca-certificates
'

echo "=== Step 4: Downloading RuneLite ==="
proot-distro login ubuntu -- bash -c '
    set -euo pipefail
    RUNELITE_URL="https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar"
    mkdir -p /root/runelite
    wget -O /root/runelite/RuneLite.jar "$RUNELITE_URL"
    echo "RuneLite downloaded: $(ls -la /root/runelite/RuneLite.jar)"
'

echo "=== Step 5: Installing X11 packages ==="
pkg install -y termux-x11-nightly pulseaudio

echo "=== Setup complete ==="
```

- `set -euo pipefail` — fail fast on any error
- Idempotent: checks if Ubuntu exists before reinstalling
- Echo markers for progress parsing

### launch-runelite.sh

```bash
#!/bin/bash
set -euo pipefail

# Start PulseAudio for game audio
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
    --exit-idle-time=-1 2>/dev/null || true

# Start Termux:X11
termux-x11 :0 &
sleep 2

# Launch RuneLite inside proot
proot-distro login ubuntu -- bash -c '
    export DISPLAY=:0
    export PULSE_SERVER=tcp:127.0.0.1

    cd /root/runelite
    java -jar RuneLite.jar --insecure-write-credentials
'
```

- PulseAudio uses TCP on localhost (proot can't use Unix sockets across boundary)
- `termux-x11 :0 &` starts X server in background
- `--insecure-write-credentials` enables credential caching for Slice 3
- Runs in **foreground mode** with session action `"0"` for X11 display handoff

---

## UI

### Layout

```
+--------------------------------------+
|  RuneLite for Tablet                 |
|                                      |
|  [check] Install Termux       Done   |
|  [check] Install Termux:X11   Done   |
|  [warn]  Configure Permissions Action |
|  [spin]  Install Linux Env  Running  |
|  [ o ]   Install Java       Pending  |
|  [ o ]   Download RuneLite   Pending  |
|  [ o ]   Verify Setup        Pending  |
|                                      |
|  +------------------------------+    |
|  | Installing Linux env...      |    |
|  | proot-distro install ubuntu  |    |
|  +------------------------------+    |
|                                      |
|  [ Retry ]              [ Launch > ] |
|                        (disabled)    |
+--------------------------------------+
```

### Compose Structure

```kotlin
@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val steps by viewModel.steps.collectAsState()
    val canLaunch by viewModel.canLaunch.collectAsState()
    val currentOutput by viewModel.currentOutput.collectAsState()

    Column {
        Text("RuneLite for Tablet", style = MaterialTheme.typography.headlineMedium)

        steps.forEach { step ->
            StepItem(
                label = step.label,
                status = step.status,
                onClick = { if (step.status is ManualAction) viewModel.onManualStepClick(step) }
            )
        }

        if (currentOutput != null) {
            OutputCard(text = currentOutput, isError = ...)
        }

        Spacer(modifier = Modifier.weight(1f))

        Row {
            if (steps.any { it.status is Failed }) {
                OutlinedButton(onClick = { viewModel.retry() }) { Text("Retry") }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { viewModel.launch() }, enabled = canLaunch) {
                Text("Launch RuneLite")
            }
        }
    }
}
```

### SetupViewModel

```kotlin
class SetupViewModel(
    private val orchestrator: SetupOrchestrator,
    private val commandRunner: TermuxCommandRunner
) : ViewModel() {
    val steps: StateFlow<List<StepState>>
    val canLaunch: StateFlow<Boolean>        // true when all steps completed
    val currentOutput: StateFlow<String?>

    fun startSetup() { viewModelScope.launch { orchestrator.runSetup() } }
    fun retry()      { viewModelScope.launch { orchestrator.retryCurrentStep() } }
    fun launch()     { commandRunner.launch("~/scripts/launch-runelite.sh") }

    fun onManualStepClick(step: StepState) {
        // Show bottom sheet with permission instructions + "Verify" button
    }
}
```

### StepItem Component

```kotlin
@Composable
fun StepItem(label: String, status: StepStatus, onClick: () -> Unit) {
    Row(verticalAlignment = CenterVertically) {
        when (status) {
            Pending      -> Icon(outlined circle, gray)
            InProgress   -> CircularProgressIndicator(small)
            Completed    -> Icon(checkmark, green)
            Failed       -> Icon(X, red)
            ManualAction -> Icon(warning, amber)
        }

        Text(label, modifier = Modifier.weight(1f))
        Text(status.displayText, color = status.color)
    }
}
```

### Manual Step: Configure Permissions

Bottom sheet with three instructions:

1. Open Termux and run: `echo "allow-external-apps=true" >> ~/.termux/termux.properties` (with copy button)
2. Go to Settings > Apps > RuneLite for Tablet > Permissions > enable "Run commands in Termux"
3. Go to Settings > Apps > Termux > Battery > Unrestricted

"Verify Setup" button sends test `RUN_COMMAND` (`echo "ok"` background). If result returns, step is marked complete.

---

## Error Handling & Edge Cases

### Per-Step Error Handling

| Step | Possible Failures | Recovery |
|------|------------------|----------|
| Install Termux | No internet; user cancels install dialog; signing key conflict (F-Droid version installed) | Retry. Signing conflict: detect, show "Uninstall existing Termux first" |
| Install Termux:X11 | Same as above | Same as above |
| Configure Permissions | User doesn't complete manual steps | "Verify" re-checks. Shows which sub-step failed |
| Install Linux Env | Termux killed by Android; network failure; disk full | Retry (idempotent). Show stdout in OutputCard |
| Install Java | `apt-get` fails (mirror down, disk full) | Retry. Show error output |
| Download RuneLite | GitHub unreachable | Retry. Show error output |
| Verify Setup | Any component missing | Show which check failed |

### Critical Edge Cases

**Termux killed mid-setup**: Timeout on CompletableDeferred (10 min). On timeout: "Setup may have been interrupted. Tap Retry." Scripts are idempotent.

**App process death**: Steps 1-3 survive (packages/permissions persist). Steps 4-7 can detect completion on re-launch via verification checks. Orchestrator re-evaluates and skips done steps.

**REQUEST_INSTALL_PACKAGES flow**: Check `canInstallPackages()` in `onResume`. If still false, re-prompt.

**RUN_COMMAND silently fails**: Timeout (30s quick / 10min setup). Prompt user to open Termux manually first.

**Disk space**: Don't pre-check. If `apt-get`/`wget` fails, error message in stdout will mention it. Show to user.

### Explicitly Skipped in Slice 1

- No persistent setup state (DataStore) — rely on completion checks
- No log file saving — live output in UI only
- No "View Logs" screen
- No health checks after initial setup
- No version tracking for installed packages

---

## Dependencies & Build Configuration

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Jetpack Compose BOM | 2024.12.01 | UI framework |
| Material3 | (from BOM) | Design system |
| Activity Compose | 1.9.3 | Compose integration |
| Lifecycle ViewModel Compose | 2.8.7 | ViewModel for Compose |
| OkHttp | 4.12.0 | GitHub API + APK downloads |
| kotlinx-coroutines-android | 1.8.1 | Async operations |
| kotlinx-serialization-json | 1.7.3 | GitHub API JSON parsing |

### Not Used in Slice 1

| Library | Why Not |
|---------|---------|
| Jetpack Navigation | Single screen — add in Slice 2 |
| Hilt/Koin | 15 files doesn't justify DI framework — manual constructor injection |
| Ackpine | APK install is ~50 lines — raw PackageInstaller API is fine |
| DataStore | No persistent state needed — completion checks suffice |
| AndroidX Security | No credential storage yet — Slice 3 |

### Manifest (Complete)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />

    <queries>
        <package android:name="com.termux" />
        <package android:name="com.termux.x11" />
    </queries>

    <application
        android:name=".RuneLiteTabletApp"
        android:label="RuneLite for Tablet"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.RuneLiteTablet">

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service android:name=".termux.TermuxResultService" android:exported="false" />
        <receiver android:name=".installer.InstallResultReceiver" android:exported="false" />
    </application>
</manifest>
```

### Build Config

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Kotlin**: 2.0+
- **JVM Target**: 17
- **Compose Compiler**: via Kotlin plugin

---

## Implementation Order

17 tasks in dependency order across 6 phases.

### Phase A: Project Foundation (Tasks 1-3)

| # | Task | Files |
|---|------|-------|
| 1 | Scaffold Android project | `build.gradle.kts` (root + app), `settings.gradle.kts`, `gradle/`, `AndroidManifest.xml`, `Theme.kt`, `MainActivity.kt`, `RuneLiteTabletApp.kt` |
| 2 | Create TermuxPackageHelper | `TermuxPackageHelper.kt` |
| 3 | Create shell scripts | `assets/scripts/setup-environment.sh`, `assets/scripts/launch-runelite.sh` |

**Checkpoint**: Project builds, installs on tablet, can check if Termux is installed.

### Phase B: Termux Communication (Tasks 4-6)

| # | Task | Files | Depends On |
|---|------|-------|-----------|
| 4 | Create TermuxResultService | `TermuxResultService.kt` | 1 |
| 5 | Create TermuxCommandRunner | `TermuxCommandRunner.kt` | 4 |
| 6 | Create ScriptManager | `ScriptManager.kt` | 5 |

**Checkpoint**: Can send command to Termux and get result back. Can deploy scripts.

### Phase C: APK Install Pipeline (Tasks 7-9)

| # | Task | Files | Depends On |
|---|------|-------|-----------|
| 7 | Create ApkDownloader | `ApkDownloader.kt` | 1 |
| 8 | Create ApkInstaller + InstallResultReceiver | `ApkInstaller.kt`, `InstallResultReceiver.kt` | 1 |
| 9 | Test APK install flow on tablet | (manual test) | 7, 8 |

**Checkpoint**: Can download Termux APK from GitHub, trigger system install dialog.

### Phase D: Setup Orchestration (Tasks 10-12)

| # | Task | Files | Depends On |
|---|------|-------|-----------|
| 10 | Define SetupStep + StepState | `SetupStep.kt` | -- |
| 11 | Create SetupOrchestrator | `SetupOrchestrator.kt` | 2, 5, 6, 7, 8, 10 |
| 12 | Wire orchestrator: all 7 steps | (update `SetupOrchestrator.kt`) | 11 |

**Checkpoint**: Orchestrator can run full setup sequence programmatically.

### Phase E: UI (Tasks 13-15)

| # | Task | Files | Depends On |
|---|------|-------|-----------|
| 13 | Build StepItem component | `StepItem.kt` | 1 |
| 14 | Build SetupScreen + SetupViewModel | `SetupScreen.kt`, `SetupViewModel.kt` | 11, 13 |
| 15 | Build permissions bottom sheet | (inside `SetupScreen.kt`) | 14 |

**Checkpoint**: Full UI rendering step list, responding to orchestrator state.

### Phase F: Integration & Launch (Tasks 16-17)

| # | Task | Files | Depends On |
|---|------|-------|-----------|
| 16 | Wire Launch button | (update `SetupViewModel.kt`) | 5, 14 |
| 17 | End-to-end test on tablet | (manual test) | All |

**Checkpoint / Exit Criteria**: Install APK > setup runs all steps > tap Launch > RuneLite on screen.

### Parallelism

Phases A-C can be built in parallel (no interdependencies between Termux comms and APK pipeline). Phase D ties everything together, Phase E puts UI on it, Phase F tests it.

---

## Research References

Technical details validated by research agents:

- **Termux RUN_COMMAND API**: [Wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent), [TermuxConstants.java](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java)
- **allow-external-apps cannot be auto-configured**: [Security patch e302a14](https://github.com/termux/termux-app/commit/e302a14c) blocks external writes to termux.properties
- **Termux GitHub releases**: [termux-app](https://github.com/termux/termux-app/releases), [termux-x11 nightly](https://github.com/termux/termux-x11/releases/tag/nightly)
- **F-Droid vs GitHub signing keys differ**: Cannot mix — must use GitHub for both
- **PackageInstaller session API**: Required for API 29+, `ACTION_INSTALL_PACKAGE` deprecated
- **Android 11+ package visibility**: `<queries>` block required or `getPackageInfo` fails silently
- **PendingIntent FLAG_MUTABLE**: Required on Android 12+ for Termux result callbacks
- **IntentService deprecated**: Use `Service` + `HandlerThread` instead
- **Background execution restrictions**: User must exempt Termux from battery optimization

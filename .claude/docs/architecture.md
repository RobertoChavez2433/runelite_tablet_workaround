# App Architecture

## Component Diagram

```
RuneLiteTabletApp (Application)
└── MainActivity (Activity)
    ├── SetupScreen (Compose)
    │   ├── StepItem (Compose, per step)
    │   └── collectAsState() ← SetupViewModel.uiState
    └── SetupViewModel (ViewModel)
        └── SetupOrchestrator
            ├── TermuxCommandRunner → Termux RUN_COMMAND intent
            │   └── TermuxResultService (BroadcastReceiver for results)
            ├── TermuxPackageHelper → PackageInstaller API
            │   └── InstallResultReceiver (BroadcastReceiver for install status)
            ├── ApkDownloader → OkHttp → GitHub Releases API
            ├── ApkInstaller → PackageInstaller session API
            └── ScriptManager → Asset file extraction
```

## Data Flow

```
SetupOrchestrator.runSetup()
  ↓ updates MutableStateFlow<SetupState>
SetupViewModel.uiState (StateFlow<SetupState>)
  ↓ collected by
SetupScreen via collectAsState()
  ↓ renders
StepItem for each SetupStep (status, label, error message)
```

## 7 Setup Steps

| Step | Implementation | Description |
|------|---------------|-------------|
| 1. Check Termux | TermuxPackageHelper | Verify Termux is installed |
| 2. Install Termux | ApkDownloader + ApkInstaller | Download from GitHub, install via PackageInstaller |
| 3. Grant Permissions | SetupActions callback | Guide user to enable RUN_COMMAND |
| 4. Copy Scripts | ScriptManager | Extract shell scripts from APK assets |
| 5. Setup Environment | TermuxCommandRunner | Run setup-environment.sh via Termux |
| 6. Download RuneLite | TermuxCommandRunner | Download RuneLite .jar inside proot |
| 7. Launch RuneLite | TermuxCommandRunner | Start RuneLite with X11 display |

## Async Pattern

```
TermuxCommandRunner:
  1. Generate unique execution ID (AtomicInteger)
  2. Create CompletableDeferred<TermuxResult>
  3. Store in pendingResults map (keyed by ID)
  4. Send RUN_COMMAND intent with PendingIntent for result
  5. withTimeout(timeout) { deferred.await() }

TermuxResultService:
  1. Receive broadcast from Termux
  2. Extract execution ID from intent extras
  3. Look up CompletableDeferred in pendingResults map
  4. Complete it with TermuxResult(exitCode, stdout, stderr)
```

## Threading Model

| Operation | Dispatcher | Reason |
|-----------|-----------|--------|
| Network (OkHttp) | Dispatchers.IO | Blocking I/O |
| File I/O (scripts, APK) | Dispatchers.IO | Blocking I/O |
| PackageInstaller | Dispatchers.IO | Session writes block |
| UI state updates | Dispatchers.Main | StateFlow → Compose |
| Termux command send | Dispatchers.Main | Intent requires Activity context |
| Termux result receive | Dispatchers.Main | BroadcastReceiver runs on main |

## Key Source Files

### App Core
- `RuneLiteTabletApp.kt` — Application class, manual DI root
- `MainActivity.kt` — Activity, SetupActions implementation, ViewModel creation

### Termux Layer (`termux/`)
- `TermuxCommandRunner.kt` — Send commands to Termux via RUN_COMMAND intent
- `TermuxResultService.kt` — Receive command results via BroadcastReceiver
- `TermuxPackageHelper.kt` — Check if Termux is installed

### Installer Layer (`installer/`)
- `ApkDownloader.kt` — Download APKs from GitHub Releases via OkHttp
- `ApkInstaller.kt` — Install APKs via PackageInstaller session API
- `InstallResultReceiver.kt` — Receive install status via BroadcastReceiver

### Setup Layer (`setup/`)
- `SetupStep.kt` — Sealed class for step status (Pending/InProgress/Completed/Failed)
- `SetupOrchestrator.kt` — Orchestrates all 7 setup steps sequentially
- `SetupViewModel.kt` — ViewModel exposing StateFlow<SetupState>
- `ScriptManager.kt` — Extract shell scripts from APK assets to Termux filesystem

### UI Layer (`ui/`)
- `SetupScreen.kt` — Main Compose screen showing setup progress
- `StepItem.kt` — Individual step row component
- `Theme.kt` — Material 3 theme configuration

### Shell Scripts (`assets/scripts/`)
- `setup-environment.sh` — Install proot-distro, Ubuntu, OpenJDK, dependencies
- `launch-runelite.sh` — Start RuneLite .jar with X11 display forwarding

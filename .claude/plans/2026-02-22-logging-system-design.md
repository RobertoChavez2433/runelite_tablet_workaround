# Logging System Design

**Date**: 2026-02-22
**Status**: APPROVED

## Overview

Exhaustive dual-output structured logging system for real-time ADB debugging and post-mortem analysis. Every operation, state change, performance metric, and error is logged with full context including stack traces on errors.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Output targets | Logcat + on-device file | Live ADB watching + survives buffer overflow / process death |
| Tag | Single `RLT` tag for all logcat output | Simple filter: `adb logcat -s RLT` |
| Verbosity toggle | Always on, all builds | Personal project, maximum visibility, no privacy concern |
| File writes | Async via dedicated HandlerThread | Zero impact on main thread / app performance |
| Cleanup strategy | Auto-purge on each setup run | No manual intervention, keeps tablet clean |
| Cached APK policy | Keep valid APKs (size matches) | Save bandwidth on retries; delete corrupt/partial only |
| Log file retention | 5 session files, delete oldest | Bounded disk usage |

## New Files

### `logging/AppLog.kt` — Dual-Output Structured Logger

**Singleton object** initialized in `RuneLiteTabletApp.onCreate()`.

#### Core Design

```kotlin
object AppLog {
    private const val TAG = "RLT"
    private lateinit var fileWriter: LogFileWriter
    private val startTime = SystemClock.elapsedRealtime()

    fun init(context: Context) { /* create LogFileWriter, rotate old logs */ }

    // Core methods (all levels)
    fun d(prefix: String, message: String)
    fun i(prefix: String, message: String)
    fun w(prefix: String, message: String)
    fun e(prefix: String, message: String, throwable: Throwable? = null)

    // Convenience methods
    fun lifecycle(message: String)           // [LIFECYCLE]
    fun step(stepId: String, message: String) // [STEP]
    fun cmd(execId: Int, message: String)    // [CMD]
    fun http(message: String)                // [HTTP]
    fun install(message: String)             // [INSTALL]
    fun cleanup(message: String)             // [CLEANUP]
    fun script(message: String)              // [SCRIPT]
    fun verify(message: String)              // [VERIFY]
    fun state(message: String)               // [STATE]
    fun ui(message: String)                  // [UI]
    fun perf(message: String)                // [PERF]

    // Performance snapshots
    fun memorySnapshot(): String  // heap used/free/max from Runtime
    fun diskSnapshot(context: Context): String  // free space via StatFs
    fun perfSnapshot(context: Context): String  // combined memory + disk
}
```

#### Formatting

Every log line follows this format:
```
+{elapsed_ms}ms [{PREFIX}] {message} | thread={thread_name}
```

Error-level logs append a truncated stack trace (top 10 frames):
```
+1500ms [INSTALL] SecurityException: permission denied | thread=main
  at com.runelitetablet.installer.ApkInstaller.install(ApkInstaller.kt:38)
  at com.runelitetablet.setup.SetupOrchestrator.installPackage(SetupOrchestrator.kt:148)
  ... 8 more frames
```

#### File Output

- **Path**: `context.filesDir/logs/rlt-session-{yyyyMMdd-HHmmss}.log`
- **New file per app launch**
- **Rotation on init**: Delete files older than 24h, keep max 5
- **Thread safety**: `ConcurrentLinkedQueue` for message passing to background `HandlerThread`
- **Buffering**: `BufferedWriter` with 8KB buffer, flushed every 500ms or on error/force-flush
- **Pull via ADB**: `adb pull /data/data/com.runelitetablet/files/logs/`

#### Performance Safeguards

| Concern | Mitigation |
|---------|------------|
| Main thread I/O | All file writes on background HandlerThread — log call only formats string (~1-5μs) |
| Compose recomposition | UI logging via `SideEffect {}` (post-commit, not during composition) |
| Download hot path | Progress logs capped at every 5% or 2 seconds, not every buffer read |
| Memory | ~200 bytes per entry, 1000 entries = 200KB — negligible |
| Synchronization | `ConcurrentLinkedQueue` — lock-free on hot path |
| File I/O burst | BufferedWriter batches writes, flush interval 500ms |

### `cleanup/CleanupManager.kt` — Auto-Purge on Setup Start

**Called at the top of `SetupOrchestrator.runSetup()`** before any step begins.

```kotlin
class CleanupManager(
    private val context: Context,
    private val apkDownloader: ApkDownloader  // for checking valid APK sizes
)
```

#### What Gets Cleaned

| Artifact | Location | Rule | Logged |
|----------|----------|------|--------|
| Cached APKs | `context.cacheDir/apks/` | Delete partial/corrupt (size mismatch). Keep valid. | File path, size, age, reason |
| Abandoned installer sessions | `packageManager.packageInstaller.mySessions` | Abandon all | Session ID |
| Old log files | `context.filesDir/logs/` | Keep 5 newest, delete rest | File path, size, age |
| Deployed scripts | Termux `~/scripts/` | Delete if Termux available | Script names, success/fail |

#### Behavior

- Best-effort: failures are logged with stack trace but don't block setup
- Runs on Dispatchers.IO (cleanup involves file I/O)
- Logs total: files deleted, sessions abandoned, space reclaimed, duration

## Modified Files — Log Coverage Map

### `RuneLiteTabletApp.kt`
- `AppLog.init(this)` in `onCreate()`

### `MainActivity.kt`
```
[LIFECYCLE] onCreate: savedInstanceState null/present, PID, thread
[LIFECYCLE] onResume: bindActions, recheckPermissions triggered
[LIFECYCLE] onPause: unbindActions
[PERF]      Memory + disk snapshot on each lifecycle callback
```

### `SetupViewModel.kt`
```
[STEP]  startSetup: first-call guard (compareAndSet result)
[STEP]  retry: which step being retried
[STEP]  launch: RuneLite launch attempt, success/failure + stack trace on failure
[STATE] Manual step click, permissions sheet show/dismiss
[PERF]  viewModelScope launch entry/exit
```

### `SetupOrchestrator.kt`
```
[STEP]    Every step transition: index, step ID, old→new status
[STEP]    evaluateCompletedSteps: which pre-checks passed/failed
[STEP]    executeStep: which step dispatched + wall-clock duration on completion
[STEP]    runSetupFrom: loop entry/exit, skip decisions for already-completed steps
[STATE]   Every StateFlow update (steps list, currentStep, currentOutput) with old→new values
[CLEANUP] At start of runSetup (delegates to CleanupManager)
[ERROR]   Full stack trace (10 frames) + all exception fields on any caught exception
```

### `TermuxCommandRunner.kt`
```
[CMD]  execute: execution ID, command path, args (truncated if long), workdir, background, stdin length, timeout
[CMD]  Intent built: all extras listed
[CMD]  startService: success/failure + stack trace on failure
[CMD]  Awaiting result: deferred created, timeout value
[CMD]  Result: stdout length, stderr length, exit code, error, wall-clock duration
[CMD]  Timeout: how long waited, execution ID for correlation
[CMD]  launch (fire-and-forget): command path, args, success/failure + stack trace on failure
[PERF] Thread name on each operation
```

### `TermuxResultService.kt`
```
[CMD]       onStartCommand: intent extras dump, execution ID
[CMD]       handleResult: execution ID, stdout preview (first 200 chars), stderr preview, exit code, error
[CMD]       Deferred completion: found/not-found in pendingResults map, map size
[LIFECYCLE] onCreate: HandlerThread started
[LIFECYCLE] onDestroy: pending results count being canceled, stack trace for each
```

### `ApkDownloader.kt`
```
[HTTP] fetchRelease: full URL, request headers
[HTTP] Response: status code, content-type, content-length, latency ms, time-to-first-byte ms
[HTTP] Response body preview on non-2xx (first 500 chars) + stack trace
[HTTP] Parsed release: tag, asset count, matched asset name + size, match pattern
[HTTP] download: URL, expected size, cache hit decision (file exists + size matches → skip)
[HTTP] Progress: every 5% or 2 seconds, current bytes, total bytes, throughput KB/s
[HTTP] Complete: total bytes, total duration, average throughput
[PERF] Memory delta before/after download
[PERF] Disk free before/after download
```

### `ApkInstaller.kt`
```
[INSTALL] canInstallPackages: boolean result
[INSTALL] Abandoned sessions cleanup: count, session IDs
[INSTALL] Session create: session ID, mode, APK file path + size bytes
[INSTALL] Session write: bytes written, fsync duration ms
[INSTALL] Session commit: session ID, pending intent details
[INSTALL] Timeout: duration waited
[ERROR]   IOException, SecurityException: full stack trace + message
```

### `InstallResultReceiver.kt`
```
[INSTALL] onReceive: session ID, status code, status name, message, all intent extras dump
[INSTALL] Deferred found/not-found in pendingResults map, map size
[INSTALL] Wall-clock from commit → callback (if trackable)
[ERROR]   Null intent or missing session: stack trace
```

### `ScriptManager.kt`
```
[SCRIPT] deployScripts: starting, script count, script names
[SCRIPT] Each script: name, content length bytes, deploy command
[SCRIPT] Deploy result: success/failure, duration ms
[SCRIPT] Asset read: duration ms (Dispatchers.IO)
[ERROR]  Deploy failure: stack trace + Termux result dump
```

### `TermuxPackageHelper.kt`
```
[STEP] isTermuxInstalled: result, package name checked
[STEP] isTermuxX11Installed: result, package name checked
```

### `ui/screens/SetupScreen.kt`
```
[UI] Recomposition triggered (counter)
[UI] Button clicks: Start Setup, Retry, Launch RuneLite (with current state context)
[UI] Permissions sheet: shown/dismissed
```

### `ui/components/StepItem.kt`
```
[UI] Step rendered: step ID, status, visual state change (old→new if tracked)
```

## Initialization Flow

```
RuneLiteTabletApp.onCreate()
  └─ AppLog.init(applicationContext)
       ├─ Create logs/ directory
       ├─ Rotate old log files (keep 5, delete 24h+)
       ├─ Open new session log file
       └─ Start background HandlerThread for file writes

MainActivity.onCreate()
  └─ AppLog.lifecycle("onCreate") + AppLog.perfSnapshot()

SetupOrchestrator.runSetup()
  ├─ CleanupManager.cleanup()   ← purge stale artifacts
  ├─ evaluateCompletedSteps()   ← log pre-check results
  └─ runSetupFrom(0)            ← step loop with full logging
```

## ADB Usage

```bash
# Watch all app logs live
adb logcat -s RLT

# Filter to specific category
adb logcat -s RLT | grep "\[HTTP\]"
adb logcat -s RLT | grep "\[STEP\]"
adb logcat -s RLT | grep "\[PERF\]"
adb logcat -s RLT | grep "\[ERROR\]\|\[INSTALL\]"

# Pull session log files for post-mortem
adb pull /data/data/com.runelitetablet/files/logs/ ./tablet-logs/

# Clear logcat before a fresh run
adb logcat --clear

# Watch with timestamps
adb logcat -s RLT -v threadtime
```

## Implementation Order

1. Create `logging/AppLog.kt` — the logger itself
2. Create `cleanup/CleanupManager.kt` — auto-purge system
3. Initialize in `RuneLiteTabletApp.kt`
4. Wire CleanupManager into SetupOrchestrator (manual DI)
5. Add log calls to all 12 files per coverage map above
6. Build and verify on device

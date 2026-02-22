# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [COROUTINE] 2026-02-22: TimeoutCancellationException extends CancellationException — catch order matters
**Pattern**: Catching `CancellationException` before `TimeoutCancellationException` makes the timeout handler dead code, since `TimeoutCancellationException` IS a `CancellationException`. The timeout result is never returned and the deferred leaks.
**Prevention**: Always catch `TimeoutCancellationException` FIRST, then `CancellationException` as a separate block that re-throws.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/termux/TermuxCommandRunner.kt

### [COROUTINE] 2026-02-22: OkHttp response.use{} required to prevent connection pool leaks
**Pattern**: Calling `call.execute()` without wrapping the response in `response.use {}` leaks the HTTP connection back to the pool on error. Every failed API call or interrupted download leaks a connection.
**Prevention**: Always wrap OkHttp responses in `response.use { resp -> ... }`. Response implements Closeable.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/installer/ApkDownloader.kt

### [ANDROID] 2026-02-22: Bare mutable fields accessed from multiple dispatchers need @Volatile
**Pattern**: Plain `var` fields in ViewModel-scoped objects accessed from both Main and IO dispatchers are data races on ARM64 (torn pointer reads possible).
**Prevention**: Use `@Volatile` for simple fields, `AtomicBoolean`/`AtomicInteger` for CAS operations, or `MutableStateFlow` for observable state.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [ANDROID] 2026-02-22: ViewModel created outside ViewModelProvider
**Pattern**: Constructing ViewModel directly in onCreate() bypasses lifecycle management — state lost on config changes.
**Prevention**: Always use `ViewModelProvider.Factory` + `by viewModels{}` delegate for ViewModels.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/MainActivity.kt

### [ANDROID] 2026-02-22: Activity reference held in long-lived object
**Pattern**: Passing Activity to SetupOrchestrator (ViewModel-scoped) causes memory leak on config change.
**Prevention**: Use callback interface (SetupActions) with bind/unbind in onResume/onPause.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [INSTALLER] 2026-02-22: NeedsUserAction left step in InProgress with no recovery
**Pattern**: PackageInstaller STATUS_PENDING_USER_ACTION returned false without setting Failed status — no Retry available.
**Prevention**: Always set a terminal status (Failed/Completed) before returning false from step execution.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [INSTALLER] 2026-02-22: Static bare var for cross-thread CompletableDeferred
**Pattern**: Using a single `var pendingResult` for install callbacks is not thread-safe and clobbers on concurrent installs.
**Prevention**: Use `ConcurrentHashMap<Int, CompletableDeferred>` keyed by session/execution ID (same pattern as TermuxResultService).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/installer/InstallResultReceiver.kt

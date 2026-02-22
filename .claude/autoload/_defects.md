# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

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

### [TERMUX] 2026-02-22: Execution ID collision with System.nanoTime().toInt()
**Pattern**: Truncating nanoTime to Int risks collision. Two rapid commands could get same ID.
**Prevention**: Use AtomicInteger counter for monotonically increasing IDs.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/termux/TermuxResultService.kt

### [ANDROID] 2026-02-22: Missing launcher icon causes AAPT resource linking failure
**Pattern**: Manifest references `@mipmap/ic_launcher` but no icon PNGs exist — build fails at resource linking.
**Prevention**: Always create placeholder icons when scaffolding Android projects.
**Ref**: @runelite-tablet/app/src/main/res/mipmap-*/ic_launcher.png

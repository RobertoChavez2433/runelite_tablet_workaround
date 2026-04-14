# Setup Defects

Max 5 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [ARCHITECTURE] 2026-04-14: Abstract class can't be Unsafe-allocated
**Pattern**: `UnsafeHelper.allocate(android.content.Context::class.java)` fails because Context is abstract.
**Prevention**: Use a concrete subclass like `android.app.Application` when allocating Android framework types for test stubs.
**Ref**: @testutil/UnsafeHelper.kt

### [ARCHITECTURE] 2026-04-14: Final class can't be subclassed for testing
**Pattern**: Extracting `PermissionHandler` as a concrete class then trying to subclass it in tests fails — Kotlin classes are final by default.
**Prevention**: Extract a domain interface (e.g. `PermissionChecker`) and have the concrete class implement it. Tests use a fake implementing the interface.
**Ref**: @domain/setup/PermissionChecker.kt, @setup/PermissionHandler.kt

### [ANDROID] 2026-03-09: RuneLite launcher JVM flags don't propagate to client
**Pattern**: `java -Xmx2g -Dsun.java2d.uiScale=2.0 -jar RuneLite.jar` sets flags on the LAUNCHER process. The launcher spawns a child JVM via JvmLauncher with its OWN args (-Xmx768m, no uiScale). All our custom flags are silently lost.
**Prevention**: Use `--scale 2` (launcher passes `-Dsun.java2d.uiScale=2.0` to client). Use `RUNELITE_VMARGS` env var for -Xmx/-XX flags (appended AFTER bootstrap args, last-Xmx wins). Never pass JVM flags on the `java -jar RuneLite.jar` command line expecting them to reach the client.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh, RuneLite launcher JvmLauncher.java

### [ANDROID] 2026-02-24: reconcileWithMarkers ABSENT must clear stateStore, not just step UI status
**Pattern**: `reconcileWithMarkers()` downgrades step status to Pending but doesn't clear `stateStore.isCompleted()` flag. The `executeStep()` function checks stateStore first and skips — step never re-runs.
**Prevention**: Always call `stateStore.clearCompleted(key)` alongside `updateStepStatus(index, Pending)` in ABSENT branch.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt (reconcileWithMarkers)

### [ANDROID] 2026-02-24: resetSetup/runSetupForHealth must reset orchestrator + setupStarted flag
**Pattern**: `resetSetup()` clears stateStore but leaves orchestrator's `_permissionPhase`, `_awaitingPermissionCompletion`, and `failedStepIndex` stale. `runSetupForHealth()` doesn't reset `setupStarted` so `startSetup()` no-ops on re-entry.
**Prevention**: Add `orchestrator.resetState()` method and call it from resetSetup. Reset `setupStarted.set(false)` from runSetupForHealth.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt

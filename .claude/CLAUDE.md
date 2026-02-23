# Runelite for Tablet

Tablet-optimized client for Old School RuneScape based on RuneLite.

## Quick References

- Defects: `.claude/autoload/_defects.md` (auto-loaded)
- Architecture: `.claude/docs/architecture.md`
- State: `.claude/autoload/_state.md` (auto-loaded)

## Session

- `/resume-session` — Load HOT context only
- `/end-session` — Save state with auto-archiving
- State: `.claude/autoload/_state.md` (max 5 sessions)
- Defects: `.claude/autoload/_defects.md` (max 7, auto-loaded)
- Archives: `.claude/logs/state-archive.md`, `.claude/logs/defects-archive.md`

## Agents

| Agent | Model | Purpose | Tools |
|-------|-------|---------|-------|
| `code-review-agent` | Opus | Senior Kotlin/Android reviewer (10-category checklist) | Read, Grep, Glob |
| `performance-agent` | Opus | Full-stack perf analysis (6-category pipeline) | Read, Grep, Glob, Bash |
| `security-review-agent` | Opus | Credential handling, IPC security, shell injection, network security (10-category checklist) | Read, Grep, Glob |

## Skills

| Skill | Purpose |
|-------|---------|
| `/brainstorming` | Collaborative design before implementation |
| `/implement` | Orchestrate implementation (dispatch, review, verify) |
| `/systematic-debugging` | Root cause analysis framework |
| `/resume-session` | Load HOT context on session start |
| `/end-session` | Session handoff with auto-archiving |

### When to Use What

| Situation | Use |
|-----------|-----|
| New feature or behavior change | `/brainstorming` first, then `/implement` |
| Bug or unexpected behavior | `/systematic-debugging` |
| Code quality concern | `code-review-agent` |
| Performance concern | `performance-agent` |
| Security or credential concern | `security-review-agent` |
| Starting a session | `/resume-session` |
| Ending a session | `/end-session` |

## Source File Map

### App Core
- `RuneLiteTabletApp.kt` — Application class, manual DI root
- `MainActivity.kt` — Activity, SetupActions impl, ViewModel creation

### Termux Layer (`termux/`)
- `TermuxCommandRunner.kt` — Send commands via RUN_COMMAND intent
- `TermuxResultService.kt` — Receive results via BroadcastReceiver
- `TermuxPackageHelper.kt` — Check if Termux is installed

### Installer Layer (`installer/`)
- `ApkDownloader.kt` — Download APKs via OkHttp from GitHub Releases
- `ApkInstaller.kt` — Install APKs via PackageInstaller session API
- `InstallResultReceiver.kt` — Receive install status via BroadcastReceiver

### Setup Layer (`setup/`)
- `SetupStep.kt` — Sealed class for step status
- `SetupOrchestrator.kt` — Orchestrates all 7 setup steps
- `SetupViewModel.kt` — ViewModel exposing StateFlow<SetupState>
- `ScriptManager.kt` — Extract shell scripts from APK assets

### UI Layer (`ui/`)
- `SetupScreen.kt` — Main Compose screen
- `StepItem.kt` — Individual step row component
- `Theme.kt` — Material 3 theme

### Shell Scripts (`assets/scripts/`)
- `setup-environment.sh` — Install proot-distro, Ubuntu, OpenJDK
- `launch-runelite.sh` — Start RuneLite with X11 display

## Build

```bash
cd runelite-tablet && ./gradlew build
```

Source root: `runelite-tablet/app/src/main/java/com/runelitetablet/`

## Conventions

### Kotlin/Android
- **DI**: Manual — constructor injection, wired in Application/Activity
- **Navigation**: Single-screen, state-driven content switching
- **ViewModel**: `ViewModelProvider.Factory` + `by viewModels{}` delegate
- **Lifecycle**: SetupActions callback with bind/unbind in onResume/onPause
- **IDs**: AtomicInteger for Termux execution IDs (not nanoTime)

### Coroutine Safety

| Operation | Dispatcher | Notes |
|-----------|-----------|-------|
| Network (OkHttp) | IO | Blocking I/O |
| File I/O | IO | Blocking I/O |
| PackageInstaller | IO | Session writes block |
| UI state updates | Main | StateFlow -> Compose |
| Termux intent send | Main | Requires Activity context |
| Termux result receive | Main | BroadcastReceiver |

**Rules**:
- Never swallow CancellationException — always re-throw or use specific exception types
- Always timeout CompletableDeferred.await() with withTimeout()
- Use structured concurrency — no GlobalScope

### Compose
- State hoisting — lift state to ViewModel, pass down
- `collectAsState()` for StateFlow
- No side effects in composition

### Shell Scripts
- Always `set -euo pipefail`
- Idempotent and retry-safe
- No hardcoded paths, proper quoting
- proot compatible (no FUSE/systemd/mount)

## Common Gotchas

| Area | Gotcha |
|------|--------|
| Termux | `allow-external-apps` must be set in `~/.termux/termux.properties` |
| Termux | RUN_COMMAND requires `com.termux.permission.RUN_COMMAND` permission |
| PackageInstaller | Must fsync before session commit |
| PackageInstaller | Signing conflict if different key used |
| PackageInstaller | STATUS_PENDING_USER_ACTION needs explicit user confirmation |
| proot | No FUSE — AppImage/Flatpak/Snap won't work |
| proot | No systemd — can't use systemctl |
| X11 | `DISPLAY=:0` must be set, Termux:X11 must be running |
| GitHub API | Release asset URLs need `Accept: application/octet-stream` header |

## Directory Reference

| Directory | Purpose |
|-----------|---------|
| autoload/ | Hot state loaded every session (_state.md, _defects.md) |
| agents/ | Agent definitions (code-review, performance) |
| skills/ | Skill definitions (brainstorming, implement, debugging, session mgmt) |
| docs/ | Architecture and reference documentation |
| state/ | JSON state files (PROJECT-STATE.json, FEATURE-MATRIX.json, feature-*.json) |
| logs/ | Archives (state-archive, defects-archive) — on-demand only, DO NOT auto-load |
| plans/ | Implementation plans and design specs |
| plans/completed/ | Completed plans (reference only) |
| research/ | Research findings (6 files + README) |
| memory/ | Key learnings and patterns (MEMORY.md) |

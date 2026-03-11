# Runelite for Tablet

Tablet-optimized client for Old School RuneScape based on RuneLite.

## Quick References

- Architecture: `.claude/docs/architecture.md`
- State: `.claude/autoload/_state.md` (auto-loaded)
- Feature docs: `.claude/docs/features/`
- Defects: `.claude/defects/_defects-{package}.md` (demand-loaded by agents)

## Session

- `/resume-session` — Load HOT context only (MEMORY.md + _state.md)
- `/end-session` — Save state with auto-archiving
- State: `.claude/autoload/_state.md` (max 5 sessions)
- Defects: `.claude/defects/_defects-{package}.md` (max 5 per file, demand-loaded)
- Archives: `.claude/logs/state-archive.md`, `.claude/logs/defects-archive.md`

## Agents

| Agent | Model | Purpose | Tools |
|-------|-------|---------|-------|
| `code-review-agent` | Opus | Senior Kotlin/Android reviewer (10-category checklist) | Read, Grep, Glob |
| `performance-agent` | Opus | Full-stack perf analysis (6-category pipeline) | Read, Grep, Glob, Bash |
| `security-review-agent` | Opus | Credential handling, IPC security, shell injection, network security (10-category checklist) | Read, Grep, Glob |
| `termux-shell-agent` | Sonnet/Opus | Termux IPC, shell scripts, proot, X11, GPU setup | Read, Edit, Write, Bash, Glob, Grep |
| `auth-agent` | Sonnet/Opus | Jagex OAuth, GeckoView, credentials, session validation | Read, Edit, Write, Bash, Glob, Grep |

## Skills

| Skill | Purpose |
|-------|---------|
| `/brainstorming` | Collaborative design -> spec output to `specs/` |
| `/adversarial-review` | Review specs/plans for gaps, security, constraint violations |
| `/writing-plans` | Convert approved spec into phased implementation plan |
| `/implement` | Orchestrate implementation (dispatch, review, verify) |
| `/systematic-debugging` | Root cause analysis framework |
| `/audit-config` | Read-only .claude/ health check |
| `/resume-session` | Load HOT context on session start |
| `/end-session` | Session handoff with auto-archiving |

### When to Use What

| Situation | Use |
|-----------|-----|
| New feature or behavior change | `/brainstorming` -> `/adversarial-review` (optional) -> `/writing-plans` -> `/implement` |
| Spec/plan quality check | `/adversarial-review` |
| .claude/ health check | `/audit-config` |
| Bug or unexpected behavior | `/systematic-debugging` |
| Code quality concern | `code-review-agent` |
| Performance concern | `performance-agent` |
| Security or credential concern | `security-review-agent` |
| Termux/shell work | `termux-shell-agent` |
| Auth/OAuth work | `auth-agent` |
| Starting a session | `/resume-session` |
| Ending a session | `/end-session` |

## Domain Rules

Path-triggered rule files auto-loaded when editing matching files. See `rules/` for details.

| Rule File | Path Trigger | Summary |
|-----------|-------------|---------|
| `rules/coroutine-safety.md` | `**/*.kt` | Dispatcher mapping, CancellationException, timeouts, structured concurrency |
| `rules/termux-integration.md` | `**/termux/**/*.kt` | RUN_COMMAND protocol, FLAG_MUTABLE, Bundle extraction, paths |
| `rules/shell-scripts.md` | `assets/scripts/**/*.sh` | set -euo pipefail, proot compatibility, quote escaping, CRLF |
| `rules/compose-ui.md` | `**/ui/**/*.kt` | State hoisting, collectAsState, no side effects |
| `rules/installer.md` | `**/installer/**/*.kt` | PackageInstaller fsync, signing, STATUS_PENDING_USER_ACTION |
| `rules/auth.md` | `**/auth/**/*.kt` | Jagex 2-step OAuth, GeckoView, session validation, credentials |

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

Summary — see `rules/` files for full details.

### Kotlin/Android
- **DI**: Manual — constructor injection, wired in Application/Activity
- **Navigation**: Single-screen, state-driven content switching
- **ViewModel**: `ViewModelProvider.Factory` + `by viewModels{}` delegate
- **Lifecycle**: SetupActions callback with bind/unbind in onResume/onPause
- **IDs**: AtomicInteger for Termux execution IDs (not nanoTime)

### Coroutine Safety (see `rules/coroutine-safety.md`)
- Never swallow CancellationException
- Always timeout CompletableDeferred.await() with withTimeout()
- Use structured concurrency — no GlobalScope
- Catch TimeoutCancellationException BEFORE CancellationException

### Compose (see `rules/compose-ui.md`)
- State hoisting — lift state to ViewModel, pass down
- `collectAsState()` for StateFlow
- No side effects in composition

### Shell Scripts (see `rules/shell-scripts.md`)
- Always `set -euo pipefail`
- Idempotent and retry-safe
- proot compatible (no FUSE/systemd/mount)

## Common Gotchas

Summary — see `architecture-decisions/` for full constraint details.

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
| autoload/ | Hot state loaded every session (_state.md) |
| agents/ | Agent definitions (5 agents: code-review, performance, security, termux-shell, auth) |
| agent-memory/ | Per-agent persistent memory (MEMORY.md per agent) |
| skills/ | Skill definitions (brainstorming, implement, debugging, session mgmt, writing-plans, adversarial-review, audit-config) |
| rules/ | Path-triggered convention rule files (6 files) |
| architecture-decisions/ | Per-package hard rules and soft guidelines (7 files) |
| defects/ | Per-feature defect pattern files (7 files, max 5 per file) |
| docs/ | Architecture and reference documentation |
| docs/features/ | Per-package overview + architecture docs (13 files) |
| state/ | JSON state files (PROJECT-STATE.json, FEATURE-MATRIX.json, feature-*.json) |
| logs/ | Archives (state-archive, defects-archive) — on-demand only, DO NOT auto-load |
| plans/ | Implementation plans |
| plans/completed/ | Completed plans (reference only) |
| specs/ | Brainstorming spec output |
| adversarial_reviews/ | Adversarial review reports |
| dependency_graphs/ | Writing-plans blast radius analysis |
| outputs/ | Audit-config reports |
| research/ | Research findings (9 files + README) |
| memory/ | Key learnings and patterns (MEMORY.md) |

## Unique Solved Problems

1. Termux results are in `getBundleExtra("result")` not flat extras; PendingIntent MUST be FLAG_MUTABLE or extras silently vanish
2. Jagex OAuth is 2 steps, 2 client IDs (`com_jagex_auth_desktop_launcher` then `1fddee4e-...`); `jagex:` URI uses commas not `&`; POST id_token to `/sessions` FIRST, GET `/accounts` with sessionId SECOND
3. WebView is permanently Cloudflare-blocked; port 80 is kernel-blocked on Android — GeckoView solves both by intercepting navigation before network
4. App's `filesDir` is unreadable by Termux (different UID) — deploy everything via TermuxCommandRunner stdin
5. proot exit codes lie (`/proc/self/fd` warnings = non-zero on success, triggers rootfs self-deletion) — verify with marker files or `which`, never exitCode
6. proot `--kill-on-exit` kills child JVMs — bypass JvmLauncher with `exec java -cp` so the process replaces, not spawns
7. Windows git CRLF breaks shebangs on Termux/Linux — `.gitattributes` with `eol=lf` + defensive `replace("\r","")`
8. `@Volatile var` is NOT reactive — `combine()`/`map()` won't re-evaluate when it changes; use `MutableStateFlow`
9. Sealed class companion `val` referencing its own subclass objects is null during ART static init — always `by lazy`
10. Catch `TimeoutCancellationException` BEFORE `CancellationException` (it's a subclass — wrong order = dead catch block)

# Memory: Runelite for Tablet

## Project Overview
- Tablet-optimized way to run the REAL RuneLite on Samsung Tab S10 Ultra (MediaTek Dimensity 9300+, Mali-G720 Immortalis MC12, 12-16GB RAM)
- NOT a RuneLite clone/port — runs actual RuneLite .jar with all plugins
- User plays with physical mouse + keyboard via Samsung DeX

## Architecture Decision
- Android installer app (Kotlin/Jetpack Compose) that automates Termux + proot-distro + RuneLite setup
- RuneLite runs as `.jar` inside proot Ubuntu ARM64 with OpenJDK 11
- Display via Termux:X11; Audio via PulseAudio
- AppImage/Flatpak/Snap do NOT work in proot (no FUSE)

## Slice 1 Implementation (Hardened)
- Source code at `runelite-tablet/` — full Android project
- At Slice 1: 15 Kotlin files + 2 shell scripts. Now ~30 Kotlin + 13 scripts (auth/, session/, cleanup/, logging/ added)
- Key packages: termux/, installer/, setup/, ui/
- Manual DI (no Hilt/Koin), single-screen (no Navigation)
- SetupActions callback pattern (not direct Activity ref) to avoid leaks
- ViewModelProvider.Factory + `by viewModels{}` for lifecycle safety
- AtomicInteger for Termux execution IDs (not nanoTime)
- 3-round review-fix-verify loop: 20 Kotlin fixes + 7 shell improvements, all P0/P1 resolved

## Termux RUN_COMMAND Integration — Key Facts
- Termux wraps ALL result data in a `Bundle` extra with key `"result"` — NOT flat intent extras
- Extract via: `intent.getBundleExtra("result")?.getString("stdout")` etc.
- Key names inside the Bundle: `"stdout"`, `"stderr"`, `"exitCode"` (Int), `"err"` (Int, error code), `"errmsg"` (String)
- `"err"` value of `-1` means no error (Termux default sentinel) — only `errCode > 0` is a real error
- `execution_id` stays as top-level intent extra (baked into PendingIntent template, survives merge)
- PendingIntent MUST use `FLAG_MUTABLE` (not IMMUTABLE) — Termux calls `pendingIntent.send(ctx, RESULT_OK, fillInIntent)` and fill-in extras are silently dropped with FLAG_IMMUTABLE

## Proot-Distro in Background Mode — Critical Learnings
- proot warns about `/proc/self/fd/0,1,2` when no PTY attached (background mode)
- `< /dev/null` fixes fd/0 but fd/1 and fd/2 still warn → non-zero exit
- proot-distro cleans up rootfs on non-zero exit — `|| true` doesn't help
- **Manual rootfs extraction works**: `proot --link2symlink tar xf TARBALL -C ROOTFS --strip-components=1`
- Must create `.l2s` directory for link2symlink support
- Tarball naming includes Ubuntu codename: `ubuntu-questing-aarch64-pd-v4.37.0.tar.xz`
- `proot-distro login ubuntu` only checks rootfs directory exists — works with manual extraction
- **MUST write post-extraction config**: resolv.conf (DNS), hosts, environment (PATH/locale)
- Without resolv.conf, apt-get hangs indefinitely on DNS resolution
- **MUST set DEBIAN_FRONTEND=noninteractive** for apt-get in no-PTY mode — debconf prompts hang even with `-y`
- `ls | head` under `set -o pipefail` exits non-zero when no files match — needs `|| true`
- proot operations are slow (ptrace overhead): 8-10min for rootfs extraction + Java install is normal
- Check success by output markers, not exit codes — proot fd warnings cause non-zero on success

## Termux Path Conventions
- Bash: `/data/data/com.termux/files/usr/bin/bash` (NOT `/bin/bash`)
- `$PREFIX` = `/data/data/com.termux/files/usr`
- `$HOME` = `/data/data/com.termux/files/home`
- **`$PREFIX/tmp`** is the real tmp dir, NOT `/tmp` (permission denied)
- X11 socket: `$PREFIX/tmp/.X11-unix/X0`
- Termux uses toybox coreutils: `df -k` works, `df -m` does NOT
- `termux-x11-nightly` requires `x11-repo` package first
- `apt-get update` returns non-zero without `gpgv` — use `|| true`

## Key Kotlin Patterns Learned
- Catch `TimeoutCancellationException` BEFORE `CancellationException` (it's a subclass — wrong order = dead code)
- Always `response.use {}` for OkHttp responses (Response implements Closeable, prevents connection leaks)
- Use `@Volatile` for fields accessed across dispatchers (ARM64 torn pointer risk)
- Use `ConcurrentHashMap<Int, CompletableDeferred>` for cross-thread async callbacks (not bare `var`)
- Check `coroutineContext.isActive` in blocking loops for cooperative cancellation
- Derive StateFlow with `.map().stateIn()` instead of manual `launch { collect {} }` collectors
- Wrap blocking I/O (assets, PackageInstaller sessions) in `withContext(Dispatchers.IO)`

## Auth: Key Facts
- **Jagex accounts** need ONLY 3 env vars: `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`
- **Legacy RS accounts** need: `JX_ACCESS_TOKEN`, `JX_REFRESH_TOKEN`, `JX_DISPLAY_NAME`
- Our code currently passes ALL 5 for Jagex accounts — `JX_ACCESS_TOKEN` is WRONG for Jagex accounts and may confuse the client
- `JX_SESSION_ID` DOES expire (~12 days inactivity verified). Refresh token stays valid longer but can't recreate game session without browser (Step 2 consent).
- RuneLite `--insecure-write-credentials` flag saves tokens to `~/.runelite/credentials.properties`
- WebView is Cloudflare-blocked; port 80 kernel-blocked on Android — GeckoView solves both
- **RESOLVED MISDIAGNOSIS (Session 46)**: "LOGGING_IN → LOGIN_SCREEN" was blamed on token format but was actually stale client jars (revision mismatch). See "RuneLite Client Update Mechanism" below.
- GeckoAuthActivity must save Step 1 tokens (accessToken, refreshToken) in instance fields BEFORE loading Step 2 consent — otherwise they're lost
- HTTP 400 `invalid_grant` = dead refresh token → must trigger re-auth, not continue with stale creds

## GPU: Key Facts
- RuneLite GPU plugin rewritten 2025-10-29: removed ALL compute shaders, lowered to GL 3.3 minimum. All shaders `#version 330`.
- Device has Mali-G720 (NOT Adreno) — Zink+Turnip path is Adreno-only, useless on Mali
- **Mali GPU path**: VirGL + ANGLE (Tier 1) or VirGL + native GLES (Tier 2) or software (Tier 3)
- **VirGL renders but black screen** — reversed-Z depth requires `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` (GL 4.5). VirGL only supports GL 4.3. `GL_ARB_clip_control` NOT in extension list (164 extensions checked).
- **LWJGL's `OpenGL45` checks function pointers**, not just version string. Even with `MESA_GL_VERSION_OVERRIDE=4.5COMPAT`, `OpenGL45=false` because GL 4.5 function pointers are NULL.
- **`MESA_NO_ERROR=1` silently swallows** the `glClipControl` failure. Must be UNSET during testing.
- **Fix: LD_PRELOAD shim** — Shim A (inject glClipControl via dlsym) or Shim B (flip GL_GREATER→GL_LESS + glClearDepth 0→1)
- **dlopen() cannot intercept already-resolved symbols** — must use sub-process with `LD_PRELOAD=shim.so`, NOT runtime dlopen()
- **VirGL FBO rendering completely broken** — `glClear(0,0,0,1)` on FBO produces A=0 (not 255). FBO color attachment never written to. NOT a depth issue. Need to test rendering to default framebuffer.
- **GL_MAX_VIEWPORT_DIMS** returns 2 integers — `glGetIntegerv` needs `GLint[2]` not single `GLint` (stack overflow → SIGSEGV)
- VirGL software versions: virglrenderer-android 1.3.0, ANGLE 2.1.24923, Mesa 25.2.8 (proot Ubuntu)
- Zink directly on Mali is unreliable — missing Vulkan features (`fillModeNonSolid`, `shaderClipDistance`, `logicOp`)
- llvmpipe gives OpenGL 4.5 — GPU plugin loads but performance is CPU-bound (choppy)
- Software rendering (50fps cap) works as MVP fallback

## Android Service Lifecycle with Static State
- `onDestroy()` in a Service must NOT clear static companion object state
- Static fields outlive service instances — Android can destroy/recreate freely
- Use `stopSelfIfIdle()` pattern: only `stopSelf(startId)` when no pending work remains

## Proot X11 Forwarding
- Proot's `/tmp` is isolated from Termux's `$PREFIX/tmp` — X11 socket not visible by default
- **MUST bind-mount**: `proot-distro login ubuntu --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"`
- `DISPLAY=:0` inside proot works after bind-mount
- Proot uses `--kill-on-exit` — child processes (e.g. JvmLauncher's ProcessBuilder) get killed when parent exits
- **Run target Java class directly via exec** to avoid multi-process launch chains dying in proot

## RuneLite Client Direct Launch
- Launcher downloads client jars to `~/.runelite/repository2/`
- After first launcher run, client can be launched directly: `java -cp "$REPO_DIR/*.jar" net.runelite.client.RuneLite`
- Use `exec` to replace shell process — avoids proot kill-on-exit issue
- Pass `-Drunelite.launcher.version=2.7.6` and `-Dsun.java2d.opengl=false`
- Client 1.12.17 confirmed working on aarch64

## RuneLite Client Update Mechanism
- `launch-runelite.sh` has two paths: Path A (direct `-cp` from `repository2/` jars) and Path B (run `RuneLite.jar` launcher)
- **Once `repository2/` has jars, Path A ALWAYS fires — the launcher is never invoked again**
- The launcher is what downloads updated client jars on each run. Bypassing it freezes jars at the installed version.
- `update-runelite.sh` only updates `RuneLite.jar` (the launcher), NOT the client jars in `repository2/`
- OSRS updates every Wednesday (server-side revision bump). Stale jars = revision mismatch = silent `LOGGING_IN → LOGIN_SCREEN`
- **Fix**: Delete `repository2/` before launch to force Path B, OR run the launcher explicitly, OR build an update-client-jars script
- Current versions: RuneLite 1.12.19 (Mar 4, 2026), OSRS rev236 (since Feb 1, 2026)

## OSRS Login Failure Signatures
- `LOGGING_IN → LOGIN_SCREEN` (no error message) = **revision mismatch** — server drops connection silently; check client jar freshness first
- "Incorrect username or password" = bad credentials
- "Failed to login" with message text = token/session error
- Connection error or timeout = server down or network issue

## Jagex Launcher 2.x Changes
- v2.0.0 (Dec 10, 2025): Non-admin install support
- v2.3.0 (Jan 27, 2026): "Security improvements" (details unspecified)
- v2.4.0 (Feb 26, 2026): "No longer detects legacy clients"
- Legacy Java Client killed Jan 28, 2026 (server-side)
- Normal RuneLite users unaffected — they use the launcher which auto-updates client jars

## On-Device Debug Workflow
- Build+install+launch: `./gradlew assembleDebug && adb install -r ... && adb shell am force-stop ... && adb logcat -c && adb shell am start -n ...`
- Watch logs: `adb logcat -s RLT:V` (run in background)
- App logs: `/data/data/com.runelitetablet/files/logs/rlt-session-*.log`
- Launch logs: `/data/data/com.termux/files/home/runelite-launch.log`
- RuneLite client logs: `$ROOTFS/root/.runelite/logs/client.log` (via `run-as com.termux`)
- RuneLite launcher logs: `$ROOTFS/root/.runelite/logs/launcher.log`
- **Screenshots**: `adb exec-out screencap -p > screenshot.png` — viewable by Read tool
- **Input**: `adb shell input tap X Y` / `adb shell input swipe` / `adb shell input keyevent`
- Device: R52X90378YB (Tab S10 Ultra)
- Git Bash expands `$PATH` when passing to `adb shell` — quote the whole command or use `run-as`

## User Preferences
- Wants thorough PRD with lots of back-and-forth
- Prefers agents for research to preserve context
- Moderate tech comfort — automate but allow troubleshooting
- Start personal, grow to distributable later
- Wants full implementation done in one pass (dispatched agents for parallel work)

## Operational Notes
- Background agents may get blocked by file write permissions — write files directly instead
- Always create placeholder launcher icons when scaffolding Android projects
- Gradle wrapper jar must be downloaded (not available via `gradle` CLI on this system)
- `adb push` path: use `MSYS_NO_PATHCONV=1 adb push` (NOT double slash — that doesn't work for all adb subcommands). `adb push dir/` nests into target — use `adb push dir` (no trailing slash) to push as target name
- `adb push` of directories can crash with `std::bad_alloc` on Windows Git Bash — push individual files instead
- `#!/usr/bin/env bash` shebangs don't work in Termux `run-as` context — invoke scripts via full path: `/data/data/com.termux/files/usr/bin/bash /path/to/script.sh`
- Git Bash expands `$PATH` and `$HOME` in `adb shell` commands — use hardcoded full paths instead of variable references, or ensure vars aren't expanded locally
- `LD_LIBRARY_PATH=$PREFIX/lib` BREAKS `virgl_test_server_android` — Termux's OpenSSL 3.x replaces system OpenSSL, missing deprecated symbols. Always `env -u LD_LIBRARY_PATH` before starting VirGL server
- Git Bash `input text` to Termux via adb is unreliable for complex commands — use script files instead
- GLFW 3.4 needs `XDG_RUNTIME_DIR=/tmp` and `GLFW_PLATFORM=x11` in proot env
- Stale X11 lock files (`$PREFIX/tmp/.tX0-lock`) must be cleaned before starting Termux:X11
- `TMPDIR=$PREFIX/tmp` must be set in self-bootstrap for Termux:X11 to find its temp dir via `run-as`
- Second device R5CY12JTTPX may appear — use `adb -s R52X90378YB` to target Tab S10

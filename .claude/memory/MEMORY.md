# Memory: Runelite for Tablet

## Project Overview
- Tablet-optimized way to run the REAL RuneLite on Samsung Tab S10 Ultra (Snapdragon 8 Gen 3, 12-16GB RAM)
- NOT a RuneLite clone/port — runs actual RuneLite .jar with all plugins
- User plays with physical mouse + keyboard via Samsung DeX

## Architecture Decision
- Android installer app (Kotlin/Jetpack Compose) that automates Termux + proot-distro + RuneLite setup
- RuneLite runs as `.jar` inside proot Ubuntu ARM64 with OpenJDK 11
- Display via Termux:X11; Audio via PulseAudio
- AppImage/Flatpak/Snap do NOT work in proot (no FUSE)

## Slice 1 Implementation (Hardened)
- Source code at `runelite-tablet/` — full Android project
- 15 Kotlin files + 1 utility (PendingIntentCompat) + 2 shell scripts, APK builds clean
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
- Jagex Launcher passes creds via env vars: `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`, `JX_ACCESS_TOKEN`, `JX_REFRESH_TOKEN`
- `JX_SESSION_ID` does NOT expire
- RuneLite `--insecure-write-credentials` flag saves tokens to `~/.runelite/credentials.properties`
- Jagex OAuth2 supports Android Trusted Web Activity natively

## GPU: Key Facts
- RuneLite GPU plugin needs OpenGL 4.3+ (compute shaders) or 4.0 (no compute)
- Mesa Zink translates OpenGL → Vulkan; achieves OpenGL 4.6 on Android
- Zink + Turnip (open-source Adreno Vulkan driver) = best performance path
- Software rendering (50fps) works fine as MVP
- **CONFIRMED**: llvmpipe (Mesa 25.2.8) gives OpenGL 4.5 Compatibility Profile — RuneLite GPU plugin loads fine

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
- `adb push` path: use `//data/local/tmp/` (double slash) to avoid Git Bash path mangling

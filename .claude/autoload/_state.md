# Session State

**Last Updated**: 2026-04-17 | **Session**: 78 (Task 21 + Phase 2.1 + Phase 2.2 + Phase 3-partial LANDED — native-Termux RL login screen reached via Kotlin pref toggle)

## Current Phase
- **Phase**: `spike/direct-android-surface` — Option B proven end-to-end through the UI. **Task 21 done**: Bionic librlawt.so. **Phase 2.1 done**: native launcher. **Phase 2.2 done**: Kotlin service selector via `LaunchPreferences.useNativeTermux`, ScriptManager APK-version-aware cache (fixes the S72 re-deploy bug), `rlawt-1.8-bionic.jar` ships in APK assets and deploys via on-device `unzip -p` from our APK in 141ms, adaptive UI scale computed from `displayMetrics`. **Phase 3 partial**: fontconfig + DejaVu installed; `launch-runelite-native.sh` drives RL to the login screen (Robey Wan character visible) under the native path with `RLT_NATIVE_TERMUX=1` + `RLT_UI_SCALE=2` env. **Remaining Phase 3 blocker**: GpuPlugin fails `glXChooseFBConfig` → `unable to find a fb config` → software rendering → meaningful FPS A/B is blocked until that's debugged.
- **Status**: RL renders playably under native path (software). `-Dsun.java2d.uiScale=2` + `adjustResolution:true` makes the AWT frame fill the Termux:X11 canvas at effective 2960×1848. Baseline remains 12 FPS at Varrock East Bank under proot path. Next session: diagnose the fb-config issue on Termux Mesa+virgl (needs `pkg install mesa-demos` + `glxinfo -B`).

## HOT CONTEXT — Resume Here

### ENTRY POINT FOR SESSION 79

**Critical task**: **diagnose GpuPlugin fb-config failure on Termux Mesa+virgl**. rlawt calls `glXChooseFBConfig` with RGBA8/depth24/stencil8/doublebuffer attrs; virgl returns zero matches on Termux-native even though proot-path finds them. This is the only remaining blocker between "RL renders" and "Phase 3 A/B FPS numbers". Proposed approach:
1. `pkg install mesa-demos` in Termux (user-approved step — was interrupted during S78).
2. Under Termux-native with VirGL running: `DISPLAY=:0 glxinfo -B` and `glxinfo | grep -A 30 'GLX Visuals'` to enumerate what FBConfigs virgl exposes.
3. Compare against rlawt's attr list in `third_party/rlawt/rlawt_nix.c:111–125`. Likely culprits: RGBA10 / ALPHA_SIZE / STENCIL_SIZE mismatch, or a missing `GLX_DRAWABLE_TYPE = GLX_WINDOW_BIT` config.
4. Either patch rlawt to relax its picker OR add env vars that make Mesa expose the required configs.
5. Once GPU plugin works, run Phase 3 A/B: 60s FpsPlugin capture at Varrock East Bank with `useNativeTermux` OFF (baseline, ≈12 FPS) vs ON (native, target ≥30 FPS).

**What's staged on repo**:
- Task 21: `third_party/rlawt/` (pristine upstream), `third_party/rlawt-bionic/` (NDK wiring), `scripts/build-rlawt-bionic.sh`, `runelite-tablet/app/libs/rlawt-1.8-bionic.jar`, Task 21 spec + plan, evidence logs.
- Phase 2.1: `runelite-tablet/app/src/main/assets/scripts/launch-runelite-native.sh`, spec `.claude/specs/2026-04-17-phase-2.1-native-launch-spec.md`, evidence `runelite-tablet/docs/logs/phase-2.1-native-launch-*.log`.
- Phase 2.2 Kotlin: `ui/LaunchPreferences.kt` (new), `ScriptDeployer.kt` (deployJars/getJarPath), `ScriptManager.kt` (version-aware cache + APK-unzip jar deploy), `LaunchCoordinator.kt` (script selector + computeUiScale), `AppContainer.kt` + `SetupViewModelFactory.kt` (DI wiring), tests updated.
- Phase 2.2 asset: `runelite-tablet/app/src/main/assets/libs/rlawt-1.8-bionic.jar`.
- Phase 2.2 spec + evidence: `.claude/specs/2026-04-17-phase-2.2-and-3-spec.md`, `runelite-tablet/docs/logs/phase-2.2-native-launch-evidence.log`, gitignored `phase-2.2-native-login-screen.png`.

**What's staged on device (R52X90378YB)**:
- `$HOME/.rlt/rlawt-1.8-bionic.jar` (auto-deployed by ScriptManager from APK on each launch if marker-vs-APK version differs).
- `$HOME/.rlt/deployed-version` = current APK VERSION_CODE.
- `$HOME/scripts/launch-runelite-native.sh` (auto-deployed).
- SharedPreferences: `launch_prefs.xml` has `use_native_termux=true` (manually written for testing; toggle via UI pending).
- Termux packages: fontconfig 2.17.1, ttf-dejavu 2.37 — install persists across APK updates.

**Critical knowledge — carry forward**:
- Task 21 DONE. Phase 2.1 DONE. Do not re-examine the rlawt build, the launcher script, or classpath assembly.
- Bionic dlopen does NOT honor `-Djava.library.path` for resolving NEEDED libs. Must use `LD_LIBRARY_PATH` in the launcher env:
  ```
  LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib:/data/data/com.termux/files/usr/lib
  ```
- Target ELF spec for librlawt.so was met: NEEDED `libjawt.so libGL.so.1 libGLX.so.0 libX11.so libm.so libdl.so libc.so`; SONAME `librlawt.so`; single VERNEED entry references `libc.so` with Bionic-internal `LIBC` (not glibc).
- 26 undefined symbols verified present on device via `third_party/rlawt-bionic/audit-symbols.sh` (uses `/system/bin/readelf` — Termux's binutils doesn't ship readelf through run-as; Android built-in is the only option).
- `run-as com.termux` does NOT inherit Termux env. On-device ad-hoc scripts must set HOME/PREFIX/TMPDIR explicitly, else the launcher reads `/data/user/0/com.termux/...` instead of `/data/data/com.termux/files/home/...` and preflight fails.
- `pkill -f <pattern>` self-kills when the pattern substring-matches the invoking `sh -c`'s cmdline. Escape specific path fragments (`'bash /data/...sh'`) or use toybox's version.
- RL 1.12.24 boot under Termux openjdk-21 crashes at `FontManager.<clinit>` because Termux has no fontconfig — Phase 3 prep: `pkg install fontconfig` + a TTF font.
- LWJGL native jars (`lwjgl-3.3.2-natives-linux-arm64.jar`) still hold glibc-linked .so files; expected Phase 3 blocker (plan Risk R4).
- `adb push` from Git-Bash mangles Unix paths — prefix with `MSYS2_ARG_CONV_EXCL='*'`.

**Remaining Phase 3 / follow-up work**:
1. **Diagnose GpuPlugin fb-config on Termux Mesa+virgl** — primary Phase 3 unblock. `glxinfo -B` probe + rlawt attr-list analysis (see ENTRY POINT above).
2. **LWJGL native-lib triage** — `lwjgl-3.3.2-natives-linux-arm64.jar` ships glibc-linked `.so`s; will fail Bionic dlopen if the GpuPlugin path gets them to load. Likely needs LWJGL rebuild (similar approach to rlawt Task 21).
3. **User-facing `useNativeTermux` toggle** — today the pref is flipped via `adb shell` XML write. Add a SettingsScreen checkbox when Phase 3 FPS numbers validate the native path.
4. **Remove `scripts/native-launcher-wrapper.sh`** — it was an ad-hoc Phase 2.1 test wrapper; Phase 2.2's Kotlin dispatch supersedes it. Clean up next commit.

**Task list snapshot (S78-end)**:
- Completed: **21** (rlawt rebuild), **Phase 2.1** (native launcher), **Phase 2.2** (Kotlin service selector + ScriptManager fix + jar deploy + adaptive UI scale), **Phase 3 R1+R2** (reachability proven, RL renders login screen).
- Active / next: **Phase 3 R3** (FPS A/B) — blocked on GpuPlugin fb-config.
- Pending: LWJGL triage, user-facing toggle, wrapper cleanup.
- Fallback (still): 20 (fork proot, patch seccomp — not needed).

**Open issues carried forward (unchanged)**:
- `#6` — P1, `security(auth)`: shellEscape missing `!` and does not strip CR/NL/NUL.
- `#7` — P2, `needs-repro`: UnsafeHelper abstract-class allocation.
- `#25` — P2, `needs-repro`: MESA_GL_VERSION_OVERRIDE does not unlock LWJGL OpenGL45 (LD_PRELOAD shim never shipped).
- `#43` — P2, `needs-repro`: SetupOrchestrator isSuccess marker.

### Cpuset topology on R52X90378YB (verified S76, unchanged)

| cpuset | cpus | readable as shell |
|---|---|---|
| `/` (root) | 0-7 | read-only |
| `/top-app` | 0-7 (processes inside it show Cpus_allowed=0xff) | NO — empty read |
| `/foreground` | 0-6 | yes |
| `/background` | **0-3** | yes |
| `/moderate` | **0-3** (same mask as background) | yes |

### Deployed artifacts on device (R52X90378YB, S77-end)

- RuneLiteTablet APK: unchanged since S76 (includes broken-asset `launch-runelite.sh`). Not rebuilt this session.
- Termux:X11 fork APK: unchanged since S74.
- On-device `launch-runelite.sh`: session-77 edit (`RLT_PROOT_SECCOMP:-1`) was REVERTED to `:-0` at session end. Will be overwritten by ScriptManager re-deploy on next fresh RLT process start regardless.
- Termux-native JDK stack: **new** — openjdk-21, mesa, X11 libs (see list in ENTRY POINT block). Unused until Task 21 delivers a Bionic librlawt.
- Pipeline processes: killed at S77-end (SIGTERM to `launch-runelite.sh` parent 16618 cascaded cleanly). No JVM, virgl, or proot running.

### Discovered-but-not-fixed bugs (carried forward)

- **ScriptManager re-deploy bug** (S72 origin): `ScriptManager.scriptsDeployed` companion flag prevents script re-deploy after an APK update. New `launch-runelite.sh` in the APK never reaches Termux's `$HOME/scripts/` until the app is cleared + reinstalled. Workaround used in S74-S77: `adb push` + `cp` via `run-as com.termux`. Fix: add a checksum or asset-version check so updated assets trigger re-deploy. See memory `project_script_redeploy_vs_stale_apk.md`.
- **Deploy-both-APKs**: `com.termux.x11` is a separately-installed fork APK that needs its own rebuild when Xlorie C code changes. New helper `scripts/deploy-native-to-device.sh` rebuilds + installs both + kills X server.
- **Proot seccomp-bpf fails on Samsung OneUI kernel** (new, S77): `RLT_PROOT_SECCOMP=1` engages seccomp-bpf (log confirms) but Samsung's Android 16 kernel does not route SECCOMP_RET_TRACE events to proot. Path-translating syscalls (execve, getcwd) return ENOSYS → proot-distro Ubuntu login dies → no JVM. Proot 5.1.107-70 (latest in Termux main) has no workaround. See `slice5-seccomp-ab.md`.
- **rlawt bundled linux-aarch64/librlawt.so is glibc-linked** (new, S77): blocks running RuneLite's JVM directly under Termux (Bionic libc). See `slice5-option-b-rlawt-blocker.md`. Fix = Task 21 rebuild.

**Convention still enforced**: every commit must be `type(scope): subject` + narrative body + `Reason:` trailer (see `scripts/git/commit-msg`). File defects via `tools/create-issue.ps1` — do NOT write `.claude/defects/*`.

## Blockers

**1. Proot ptrace syscall-interception caps producer throughput at ~2000 syscalls/s** (S77 diagnosis).
- Measured via `/proc` sampler: RuneLite's `Client` render thread shows 3945 nonvoluntary ctxt-switches/s at ~12 FPS scene rate. 3945 ÷ 2 ≈ 1970 syscalls/s ÷ 164 syscalls/frame (GL-plugin cost) = 12 FPS ceiling.
- `RLT_PROOT_SECCOMP=1` would drop ptrace overhead but fails on Samsung OneUI kernel (execve ENOSYS).
- Unblocks when: Phase 2.1 wires a native-Termux launcher that bypasses proot.
- Fallback: Option A (fork + patch proot 5.1.107-70's seccomp filter) — Task 20.

**Stale blockers removed (Session 78):**
- ~~"`/background` cpuset clamp on entire Termux subtree"~~ — Refuted in S76.
- ~~"rlawt bundled native lib is glibc-linked, blocks native-Termux JVM"~~ — **Resolved in S78**. NDK rebuild produced Bionic-compatible librlawt.so; drop-in `rlawt-1.8-bionic.jar` verified on device.

## Recent Sessions

### Session 78 (2026-04-17)
**Work**: Executed Task 21 end-to-end. Plan + spec saved to `.claude/plans/2026-04-17-rlawt-bionic-rebuild.md` + `.claude/specs/2026-04-17-rlawt-bionic-rebuild-spec.md`. Cloned upstream rlawt at `v1.8` (commit `ecb6599`); vendored pristine source at `third_party/rlawt/`. Built Bionic adaptation dir at `third_party/rlawt-bionic/` with NDK 28.2.13676358 wiring, device-harvested headers + stub libs, pre-generated JNI header, minimal CMakeLists. Wrote `scripts/build-rlawt-bionic.sh` (handles Git-Bash PATH quirks, uses Android SDK cmake+ninja). First build succeeded on first real attempt after fetching upstream xorgproto-2024.1 X.h (Termux libx11 ships only Xlib.h, not the X protocol headers) and Mesa 26.0.5 GL/glx.h (Termux mesa doesn't install dev headers). Output: 88800 B librlawt.so with target ELF metadata — NEEDED `libjawt.so libGL.so.1 libGLX.so.0 libX11.so libm.so libdl.so libc.so`, SONAME `librlawt.so`, single VERNEED entry referencing libc.so Bionic `LIBC` (not GLIBC_X.Y). 26 undefined symbols, all verified present on device via `audit-symbols.sh` (uses Android's built-in `/system/bin/readelf` because Termux binutils doesn't ship readelf through run-as). MiniRlawtLoad.java `SUCCESS: librlawt.so loaded`. Repackaged rlawt-1.8.jar → rlawt-1.8-bionic.jar (AWTContext.class byte-identical, only the one .so replaced). MiniAwtContext end-to-end: `AWTContext.loadNatives()` returns OK under Termux-native openjdk-21. Final layout: pristine `third_party/rlawt/` + adapted `third_party/rlawt-bionic/` + `runelite-tablet/app/libs/rlawt-1.8-bionic.jar` + `.gitignore` entry for `build-bionic/`.
**Decisions**: Use NDK cross-compile on host (not on-device Termux build) for reproducibility. Keep upstream dir untouched — put all Bionic wiring in a sibling `rlawt-bionic/` dir. Vendor GL/xorgproto headers from upstream (Termux doesn't provide GL dev headers; libx11 protocol headers missing). Runtime require `LD_LIBRARY_PATH` because Java's `-Djava.library.path` does not influence Bionic's NEEDED resolution. Two spec gates loosened from the draft: (1) "no VERNEED" revised to "VERNEED only references NEEDED libs" — Bionic internally uses LIBC versioning, so single VERNEED entry for libc.so is correct; (2) Phase 21G reduced from "live GL context creation" to "end-to-end classpath + native-load via `AWTContext.loadNatives()`" — live GL test belongs in Phase 3 A/B, not here.
**Next**: Phase 2.1 = write `launch-runelite-native.sh` (RLT_NATIVE_TERMUX=1 gate, skips proot-distro). Resolve U4 — how RL's ~/.runelite/repository2/*.jar classpath reaches Termux-native view. Then Phase 2.2 (service selector + ScriptManager re-deploy fix) and Phase 3 (FPS A/B at Varrock East Bank, target ≥ 30 FPS).

**Phase 2.1 follow-up (also S78)**: Wrote spec `.claude/specs/2026-04-17-phase-2.1-native-launch-spec.md` with 29 verification gates across U/A/B/C/D/E/S sections. Implemented `runelite-tablet/app/src/main/assets/scripts/launch-runelite-native.sh` — gated by `RLT_NATIVE_TERMUX=1`, reuses X11/virgl/PulseAudio setup from proot launcher, skips proot-distro entirely, scans `repository2/` live (stored `direct-classpath.txt` was stale), prepends our `rlawt-1.8-bionic.jar` while excluding stock `rlawt-1.8.jar`, invokes `$PREFIX/lib/jvm/java-21-openjdk/bin/java` with `LD_LIBRARY_PATH` covering JDK libs + Termux usr lib. U4 resolved: rootfs paths are reachable from Termux (`$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/root/.runelite/repository2/*.jar`). Also wrote `scripts/native-launcher-wrapper.sh` — a double-fork ad-hoc test wrapper that sets HOME/PREFIX correctly for `run-as com.termux` ad-hoc invocations (redundant once Phase 2.2 wires the Kotlin service). **On-device verification (R52X90378YB)**: JVM PID 19041 started under Termux-native openjdk-21.0.10 with no proot in process tree; 8652 class loads including `net.runelite.client.RuneLite` and `RuneLiteModule`; zero UnsatisfiedLinkError, zero dlopen failures; RL's own logger printed `RuneLite 1.12.24 ... starting up, args: --insecure-write-credentials --debug` on the `[main]` thread. RL then crashed at `FontManager.<clinit>` with `Fontconfig head is null` because Termux has no fontconfig / no TTF fonts installed — this is POST main-class-load (so Phase 2.1 exit satisfied) and becomes Phase 3 prep (`pkg install fontconfig` + a TTF font). LWJGL natives not exercised this run (crash before GL plugin load); expected Phase 3 blocker (plan Risk R4).

**Phase 2.1 decisions**: Regenerate classpath on every launch (don't trust `direct-classpath.txt` cache — versions drift). Set `-Duser.home` to the rootfs `.runelite` dir so the native JVM shares configs with the proot path (no divergence, easy A/B). PulseAudio kept in Phase 2.1 (draft wrongly said skip) because RL static-init reads PULSE_SERVER regardless. Ad-hoc wrapper is explicitly temporary; remove in Phase 2.2.

### Session 77 (2026-04-17)
**Work**: Wrote `scripts/jvm-wait-sampler.sh` + `scripts/jvm-wait-analyze.py`. 60s /proc capture at Varrock East Bank proved RuneLite's `Client` thread has 3945 nonvol-ctxt-switches/s — synchronous ptrace interception is the FPS bottleneck (not cpuset, not VirGL IPC, not Mesa). A/B'd `RLT_PROOT_SECCOMP=1`: engaged seccomp-bpf correctly but Samsung OneUI kernel doesn't route SECCOMP_RET_TRACE → execve ENOSYS → launch fails; reverted. Proot 5.1.107-70 is latest in Termux main. User widened scope (Termux is forkable, RLT-app editable; RL + Android off-limits). Chose Option B (native-Termux JVM). Installed openjdk-21, mesa, X11 libs, patchelf. rlawt's bundled `linux-aarch64/librlawt.so` confirmed glibc-linked (NEEDED libc.so.6 + ld-linux-aarch64.so.1 + VERNEED/VERSYM). Four YOLO workarounds probed: symlink→bionic paths, re-symlink to permitted namespace, `patchelf --remove-needed` + `--replace-needed`, `gobjcopy --remove-section=.gnu.version*`. Each got past one Bionic-linker layer and hit the next. Clean fix = rebuild rlawt via Android NDK (Task 21, 2-4h).
**Decisions**: Proot ptrace is the confirmed bottleneck. Option B chosen over Option A. Rebuild rlawt from source is the only viable unlock. Option A (patch proot seccomp) deferred as fallback. Two durable memories added (`project_ptrace_is_the_fps_bottleneck.md`). Three new analysis docs under `runelite-tablet/docs/logs/` (jvm-wait-analysis, seccomp-ab, option-b-rlawt-blocker).
**Next**: S78 = execute Task 21. Clone `github.com/runelite/rlawt`, NDK-build for aarch64-linux-android/Bionic, verify against `MiniRlawtLoad.java` on device, repackage jar. Then unblock Phases 1.3 → 2.1 → 2.2 → Phase 3 FPS measurement.

### Session 76 (2026-04-17)
**Work**: Re-launched RLT with S75's `TermuxProcessPin` Path A after recovering from the ScriptManager re-deploy bug (APK shipped the broken pre-fix `launch-runelite.sh`; fresh process → re-deploy from APK → on-device script was broken again → 1st launch attempt silently crashed at the `set -u` multi-assign line). Re-pushed the fix, then got two device attempts:
- Attempt 2: `cpuset=/moderate` (0-3 CPU mask, same as /background) — RLT activity wasn't foreground when bind fired; Samsung placed BOUND_TOP in /moderate.
- Attempt 3: `cpuset=/top-app` on all three markers (launch-script-entry, virgl-ready, java-started). JVM main + virgl `Cpus_allowed_list: 4-7`. JVM sub-threads `0-7`. Perfect scheduler state.

60s FpsPlugin capture at Varrock East Bank: **12-15 FPS** (S74 baseline was 13). Zero meaningful gain. Per-core CPU delta under live gameplay: ~27% utilization across all 8 cores — no core pegged, system 70%+ idle. **The pipeline is not CPU-bound, and cpuset was never the FPS gate.**

**Decisions**: Path A is **refuted** as an FPS fix. Further scheduler/cpuset work is explicitly off the table. Future sessions must measure where the JVM actually waits before proposing any larger refactor — see `runelite-tablet/docs/logs/slice5-pathA-results.md` for full A/B + Path B recommendation ranking. Memory updated with two durable learnings (`project_cpuset_is_not_the_fps_bottleneck.md`, `project_script_redeploy_vs_stale_apk.md`).

**Next**: S77 does NOT start writing code. First step is profiling — `strace -c`, `/proc/$JVM_PID/schedstat`, or similar — to find the top syscall-wait sink. Cheap try: `RLT_PROOT_SECCOMP=1` env flag (~1 LoC). All files from S75 remain uncommitted on the spike branch.

### Session 75 (2026-04-16, paused mid-measurement)
**Work**: Chose Path A (Termux bind-hoist) over Path B (bundle virgl+proot into RLT service) on cost grounds — ~50 LoC vs 200+. Wrote `TermuxProcessPin` (new) + wired into `RuneLiteSessionService` lifecycle (pin after startForeground, unpin on stop/destroy/health-stopped). Added cpuset instrumentation to `launch-runelite.sh` (entry, virgl-ready, java-started). Built + deployed RLT APK; pushed updated script into Termux home via adb workaround. First launch: `bindService` succeeded (flags=0x1041, onServiceConnected with real binder). Android AMS confirmed Termux entered `PROCESS_STATE_BOUND_TOP` under our bind — encouraging first signal. But launch script silently crashed at line 24 before writing any log because `local a="$1" b="$2" c="/proc/$b/cpuset"` under `set -u` failed on unbound `$b` during `c`'s RHS expansion. Fixed by splitting into separate `local` lines; deployed fix; cleaned device for S76.
**Decisions**: Path A first (cheap, directly tests cpuset hypothesis). If S76 shows cpuset still `/background`, Path B is the only remaining option. Gate: primary signal is the new `CPUSET launch-script-entry` log line, secondary is FpsPlugin overlay vs S74's 13 FPS baseline.
**Next**: S76 relaunches RLT, verifies `CPUSET launch-script-entry pid=… cpuset=/top-app` (or `/foreground`) in `runelite-launch.log`, captures scene FPS at Varrock East Bank, decides Path A-ship vs Path B-pivot.

### Session 74 (2026-04-16, paused mid-arc)
**Work**: Executed Slice 4 sticky-AHB-lock per plan + discovered session 72's "99.6% accel claim" was in the pre-S74 state because the running X server was loading `libXlorie.so` from a separately-installed `com.termux.x11` fork APK — not RuneLiteTablet's bundled copy. First half of the session was misrouted: installed RLT APK, X server ran stale code, no AhbLockTrace lines in logcat. Fixed by rebuilding `third_party/termux-x11-upstream` and installing `app-arm64-v8a-debug.apk` as `com.termux.x11`. Authored `scripts/deploy-native-to-device.sh` so the two-APK build is repeatable. Implemented Slice 4 (counters + `RLT_STICKY_AHB_LOCK` flag gated by `$HOME/.rlt-sticky-ahb` sentinel). A/B on device (Varrock East Bank, FpsPlugin overlay):
- Baseline (sticky=0): lock_calls=~240/5s, sticky_hits=~4800/5s, damage-redraws=47 FPS, scene=13 FPS.
- Sticky (sticky=1): lock_calls=0, flush_skipped=~234/5s, damage-redraws=47 FPS, scene=13 FPS.
- **Delta: 0.** Sticky lock works correctly, has zero FPS impact. The compositor is already sticky-by-default via CreatePixmap's initial lock.

Pivoted to root cause: `/background` cpuset clamp on Termux subtree. Proved by spawning virgl via `run-as com.termux` (cpuset `/`) + taskset 0xF0 → Cpus_allowed_list: 4-7. Added `.rlt-external-virgl` sentinel to launch-runelite.sh that skips its own virgl spawn and adopts the pre-spawned pinned one. Second A/B: scene FPS still 13. Conclusion: virgl CPU isn't the gate — the JVM (inside proot inside Termux `/background`) is. JVM can't be moved without spawning proot from `/top-app`.

**Decisions**: Slice 4 **landed but measured null**; default-OFF sentinel left in place as opt-in. The plan's slice sequence (S5 direct SurfaceView next) was based on "compositor is the bottleneck" premise — now refuted. Cpuset is the architectural block. Script-deployment cache bug (ScriptManager's `scriptsDeployed` companion) discovered: APK install doesn't re-deploy scripts to Termux home, worked around via `adb push + run-as cp` in S74.

**Next**: Session 75 decides between (a) foreground-service-hosted pipeline (~200+ LoC to unlock big/prime cores for JVM+virgl), (b) confirm 13 FPS ceiling with one more ptrace/seccomp test, or (c) document ceiling and accept.

### Session 73 (2026-04-16)
**Work**: Ported Field Guide's commit convention + authored a parallel issue convention. Installed `scripts/git/commit-msg`, `scripts/git/valid-scopes.txt`, `.gitmessage`, `tools/create-issue.ps1`, and `tools/migrate-defects-to-issues.sh`. Three parallel audit agents classified all 56 local defects (1 OPEN, 51 RESOLVED, 2 STALE, 3 UNKNOWN). Created 54 GitHub issues on `RobertoChavez2433/tablite` (50 closed as historical, 4 open: #6 security + #7/#25/#43 needs-repro). Deleted `.claude/defects/` + archive; updated `CLAUDE.md` and `end-session` skill.
**Decisions**: Defects are GitHub-only going forward. Commit hook is enforced on every commit. `type(scope): subject` + narrative body + `Reason:` trailer is mandatory for `feat`/`fix`/`refactor`/`perf` and for scoped lightweight commits.
**Next**: Session 74 returns to FPS work (sticky AHB lock) + clears the 3 needs-repro issues.

### Session 72 (2026-04-16)
**Work**: Executed Slice 1-3 of direct-surface plan on R52X90378YB. Extended `DamageTraceV2` with dladdr offset + rerouted to `__android_log_print` (ErrorF isn't captured in this build). Named hot path: `exaPutImage` @ `exa_accel.c:252`, `exaComposite` @ `exa_render.c:887`. Discovered `lorieExa` has no `UploadToScreen` → 100% software fallback. Implemented `lorieUploadToScreen` (direct AHB writes) → 99.6% accel. Damage-redraw rate 44-51 → 60-66 FPS. Also fixed `ScriptManager` re-deploy timeout (promoted cache flag to companion-object so broadcast-created instances share state).
**Decisions**: 120 FPS gate not hit; ~25% improvement over session 71 baseline. Prior "VirGL ceiling" framing was wrong — root cause was software PutImage, not VirGL. Sticky-lock in `UploadToScreen` is the next cheap lever (~20 LoC, expected 2-3×).
**Next**: Session 73 adds sticky AHB lock + measures in-game FPS via RuneLite FpsPlugin overlay (needs interactive login).

## Active Plans

- **Phase 9: Comprehensive Logging System** — **COMPLETE + DEVICE-VERIFIED**. 127/128 spec items. 210 tests. All layers verified on device.
- **Clean Architecture Refactor (Phases 1-8)** — **COMPLETE**.
- **Presentation Pipeline 120 FPS** — **IN PROGRESS** (`.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`). Slices 1-3 complete. Slice 4 null. Path A (S75-S76) refuted. **S77 diagnosed proot ptrace as the actual bottleneck.** Option B (native-Termux JVM) chosen; blocked on rlawt Bionic rebuild (Task 21). Old plan's slice sequence is effectively superseded by Option B's phases 1-4; update the plan doc once Task 21 lands.

## Reference
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Test code**: `runelite-tablet/app/src/test/java/com/runelitetablet/`
- **Native code**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/`
- **Debug docs**: `runelite-tablet/docs/debug-logging.md`
- **FPS diagnosis (session 77)**: `runelite-tablet/docs/logs/slice5-jvm-wait-analysis.md`, `slice5-seccomp-ab.md`, `slice5-option-b-rlawt-blocker.md`
- **FPS research (session 70)**: `runelite-tablet/docs/fps-ceiling-research-session70.md`
- **Device logs (session 69)**: `runelite-tablet/docs/logs/2026-04-14-device-verification-rlt.log` (10K lines), `*-native.log` (1.4K lines), `*-full.log` (149K lines)

# Session State

**Last Updated**: 2026-04-17 | **Session**: 78 (Phase 2.1 + 2.2 + 3-partial LANDED; DIAG logging expanded; GPU-plugin blocker ROOT CAUSED to Termux Mesa, not rlawt/our pipeline)

## Current Phase
- **Phase**: `spike/direct-android-surface` — Option B code path proven end-to-end. **Task 21 done**, **Phase 2.1 done**, **Phase 2.2 done** (Kotlin `LaunchPreferences.useNativeTermux` + APK-version-aware ScriptManager cache + on-device `unzip -p "$APK" assets/libs/…` jar deploy in 141ms + adaptive UI scale from `context.resources.displayMetrics`). **Phase 3 partial**: fontconfig installed, RL renders login screen under native path (Robey Wan character visible in Lumbridge bank). **Phase 3 blocker ROOT-CAUSED** to Termux Mesa's client-side GLX handshake — same error from `glxinfo`, `glxgears`, and rlawt; proot-Ubuntu Mesa against the SAME X server succeeds past FBConfig enumeration. Our pipeline, rlawt, and RuneLite are fine; the bug is in Termux Mesa 26.0.5 vs Ubuntu Mesa behavior.
- **Status**: Immersive system bars fix shipped (status bar + launcher dock no longer eat screen on Tab S10 Ultra). DIAG preflight expanded and wired into `launch-runelite-native.sh` (dumps Termux:X11 prefs, X server processes, VirGL state, Mesa env, glxinfo, RL profile window prefs, rlawt jar). HybridX11HostActivity gained `WINDOW`-tagged AppLog on onCreate + insets + LorieView.changed callback. User feedback that we were "implementing without verifying" addressed by this logging pass. Session 79 plan is in `.claude/specs/2026-04-17-session-79-glx-handshake-debug-plan.md` — research + logging-extensions first, then A/B tests, then implementation.

## HOT CONTEXT — Resume Here

### ENTRY POINT FOR SESSION 79

**Plan is written**: `.claude/specs/2026-04-17-session-79-glx-handshake-debug-plan.md`. Do research + logging extensions FIRST (Sections A+B), then A/B testing (Section C), then implementation (Section D). Run Section E (window-size fix via RL profile `gameSize` + stretched-mode) in parallel — it's orthogonal to the Mesa GLX issue.

**Critical finding to carry forward** (S78-end, captured in `docs/logs/phase-2.2-diag-preflight-evidence.log`):
- `glxinfo -B` on native Termux: `Error: couldn't find RGB GLX visual or fbconfig` (identical error to rlawt's `glXChooseFBConfig` failure).
- `glxinfo -B` inside proot-Ubuntu against the same Termux:X11 server: enumerates FBConfigs successfully; only fails later at `X_GetImage` (BadMatch, downstream).
- Every env permutation on Termux failed identically: default, `LIBGL_ALWAYS_SOFTWARE=1`, `LIBGL_ALWAYS_INDIRECT=1`, explicit `LIBGL_DRIVERS_PATH`, `LIBGL_DEBUG=verbose` (produces NO output — fail is earlier than the debug hook).
- EGL also fails on Termux (`eglInitialize failed`), so EGL-backed GLX fallback is out.
- DRI drivers ARE present in `$PREFIX/lib/dri/` (including `swrast_dri.so`, `zink_dri.so`, `virtio_gpu_dri.so`, `kms_swrast_dri.so`).
- **Conclusion**: the bug is in Termux Mesa 26.0.5's client-side GLX handshake, NOT rlawt/RuneLite/our pipeline. First step next session: B3 (test `LIBGL_DRI3_DISABLE=1` — NOT tried in S78), and A1 (strace-diff Termux Mesa vs proot Ubuntu Mesa side-by-side to see where their X protocol diverges).

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

**1. Termux Mesa client-side GLX handshake produces zero FBConfigs against Termux:X11** (S78 end).
- `glxinfo -B` under Termux: `Error: couldn't find RGB GLX visual or fbconfig` — identical to rlawt's `glXChooseFBConfig` failure.
- Same query inside proot-Ubuntu against the SAME X server: succeeds past FBConfig enumeration.
- Delta is in Termux Mesa 26.0.5 vs Ubuntu Mesa client behavior, NOT our pipeline.
- Evidence: `runelite-tablet/docs/logs/phase-2.2-diag-preflight-evidence.log`.
- Unblocks when: the Mesa client library finds FBConfigs (options: Termux Mesa patch, Ubuntu-Mesa LD_LIBRARY_PATH override in the launcher, Xlorie patch to advertise the extensions Termux Mesa expects).
- Session 79 plan: `.claude/specs/2026-04-17-session-79-glx-handshake-debug-plan.md`.

**2. RL AWT frame renders at 765×503 cached gameSize, wastes the rest of the canvas**.
- Observed at end of S78: Termux:X11 canvas occupies the full physical screen (immersive fix worked), but RL's `ContainableFrame` is small because the stored profile has `runelite.gameSize=765x503`.
- Orthogonal to the Mesa GLX blocker: can be fixed by patching `$ROOTFS/root/.runelite/profiles2/*.properties` at launch (Session 79 Section E).

**Stale blockers removed:**
- ~~Proot ptrace syscall-interception (S77 diagnosis)~~ — **Resolved**. Phase 2.1 + Phase 2.2 wire a native-Termux launcher that skips proot entirely.
- ~~rlawt bundled native lib is glibc-linked~~ — **Resolved in S78 Task 21**.
- ~~"`/background` cpuset clamp on entire Termux subtree"~~ — Refuted in S76.

## Recent Sessions

### Session 78 (2026-04-17)
**Work**: 4 commits landed end-to-end: (1) **Task 21** `build(native): rebuild rlawt for Bionic via Android NDK` — vendored upstream rlawt v1.8, built Bionic aarch64 librlawt.so via NDK 28.2.13676358, repackaged rlawt-1.8-bionic.jar. MiniRlawtLoad + MiniAwtContext verified on device. (2) **Phase 2.1** `scripts(native): add launch-runelite-native.sh` — new launcher gated by `RLT_NATIVE_TERMUX=1`, skips proot-distro, regenerates classpath live from repository2/, swaps in the Bionic rlawt jar. JVM reaches RL main class with zero UnsatisfiedLinkError / dlopen failures. (3) **Phase 2.2** `feat(scripts): wire native-Termux launch path via LaunchPreferences` — Kotlin `ui/LaunchPreferences.useNativeTermux` + APK-version-aware ScriptManager cache (fixes S72 re-deploy bug via `$HOME/.rlt/deployed-version` marker compared to BuildConfig.VERSION_CODE) + `deployJars()` via on-device `unzip -p "$APK" assets/libs/…` (141ms, vs 30s timeout on base64-stdin) + adaptive UI scale computed from `context.resources.displayMetrics` passed through `RLT_UI_SCALE` env. (4) **Logging expansion** `logging(native): add DIAG preflight + WINDOW callback logs, isolate GLX blocker` — after user pointed out we were guessing at GPU/fullscreen issues, added bash-side DIAG preflight (Termux:X11 prefs, X server processes via correct `com.termux.x11.Loader`/`CmdEntryPoint` cmdline pattern, VirGL state, full Mesa env, glxinfo output, RL profile prefs, rlawt jar state) + HybridX11HostActivity WINDOW-tagged AppLog (onCreate displayMetrics + window flags + cutout mode, `WindowInsetsControllerCompat.hide(systemBars)` + `SHORT_EDGES` cutout, onApplyWindowInsets listener, LorieView.changed callback with surface/screen/display ratios). Also installed Termux fontconfig + ttf-dejavu (RL FontManager crash fixed).

**Decisions** / **findings**:
- Task 21 spec gates loosened: "no VERNEED" → "VERNEED only references Bionic sonames" (Bionic internally uses LIBC versioning, single libc.so VERNEED entry is correct); Phase 21G reduced to "end-to-end classpath + native-load via AWTContext.loadNatives()" — live GL context creation belongs in Phase 3 A/B.
- Jar deploy via APK `unzip -p` sidesteps Android Binder parcel limits (347KB base64 stdin timed out).
- UI scale NEVER hardcoded in script — Kotlin computes from displayMetrics, bucketed 1/1.5/2/2.5 at 0/1800/2400/3200 px. Memory `feedback_no_hardcoded_ui_sizes.md` codifies the rule.
- **GPU plugin blocker ROOT-CAUSED to Termux Mesa, not our pipeline**: DIAG preflight showed `glxinfo -B` on native Termux fails identically to rlawt (`Error: couldn't find RGB GLX visual or fbconfig`), while proot-Ubuntu Mesa against the SAME Termux:X11 server successfully enumerates FBConfigs (only fails later at `X_GetImage` BadMatch). Every env permutation failed identically on Termux: `LIBGL_ALWAYS_SOFTWARE=1`, `LIBGL_ALWAYS_INDIRECT=1`, explicit `LIBGL_DRIVERS_PATH`, `LIBGL_DEBUG=verbose` (produces NO output — failure is before the debug hook). EGL also fails (`eglInitialize failed`). DRI drivers are all present. The bug is in Termux Mesa 26.0.5's client-side GLX handshake vs Ubuntu Mesa's. **RuneLite, rlawt, our launcher, and Kotlin wiring are all fine.**
- Immersive system bars fix shipped — Tab S10 Ultra status bar + launcher dock no longer eat screen pixels under native path.
- RL `runelite.gameSize=765x503` cached from prior session causes small frame; fix ties to either GPU-plugin-enabled stretched mode OR explicit profile patching (Session 79 Section E).

**Next**: Session 79 plan at `.claude/specs/2026-04-17-session-79-glx-handshake-debug-plan.md` — research + logging extensions first (Sections A+B), then A/B tests (Section C), then implementation (Section D). Run window-size fix (Section E: adaptive `runelite.gameSize` + stretched mode) in parallel since orthogonal to Mesa GLX. Do NOT restart the rabbit-hole cycle of guessing env vars; next step is a Termux-vs-proot `strace` diff of the GLX protocol handshake (A1) + `LIBGL_DRI3_DISABLE=1` test (B3, not tried in S78).

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

*(Session 73 archived to `.claude/logs/state-archive.md` at S78 end.)*

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

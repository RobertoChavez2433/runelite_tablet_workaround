# Session State

**Last Updated**: 2026-04-17 | **Session**: 78 (Task 21 LANDED — rlawt rebuilt for Bionic, drop-in jar verified on device)

## Current Phase
- **Phase**: `spike/direct-android-surface` — Option B unblocked. **Task 21 complete**: rebuilt rlawt via Android NDK 28.2.13676358. Drop-in `rlawt-1.8-bionic.jar` loads under Termux-native openjdk-21 with zero proot involvement. All NEEDED libs Bionic-sonamed, all 26 undefined symbols resolved on device. Phase 2.1 (native-Termux launch script) is next and no longer blocked.
- **Status**: Pre-launch integration proven (`AWTContext.loadNatives()` succeeds end-to-end via jar classpath under Termux). FPS not yet re-measured — that lands in Phase 3 A/B (post-Phase 2.1). Baseline remains 12 FPS at Varrock East Bank under proot path; expected ≥ 30 FPS on native path (ptrace removed), spike goal ≥ 60, S-FINAL target ≥ 100.

## HOT CONTEXT — Resume Here

### ENTRY POINT FOR SESSION 79

**Critical task**: **Phase 2.1** — write `launch-runelite-native.sh` (new) that skips proot-distro entirely and runs RuneLite's JVM directly under Termux-native openjdk-21. Gated by `RLT_NATIVE_TERMUX=1`. See plan `.claude/plans/2026-04-17-rlawt-bionic-rebuild.md` §Phase 2.1.

**What's staged on repo**:
- `third_party/rlawt/` — pristine upstream at v1.8 (commit `ecb6599`).
- `third_party/rlawt-bionic/` — NDK build wiring, harvested headers + stub libs, AUDIT.md, README.md.
- `scripts/build-rlawt-bionic.sh` — reproducible build (`bash scripts/build-rlawt-bionic.sh --clean` regenerates `librlawt.so`).
- `runelite-tablet/app/libs/rlawt-1.8-bionic.jar` — drop-in replacement (260 KB, only the `net/runelite/rlawt/linux-aarch64/librlawt.so` entry differs from stock).
- Evidence logs: `runelite-tablet/docs/logs/rlawt-bionic-load-success.log`, `rlawt-bionic-awt-smoke.log`.

**What's staged on device (R52X90378YB)**:
- `$HOME/rlawt-test/librlawt-bionic.so` — 88800 B, Bionic-linked, loads cleanly.
- `$HOME/rlawt-test/rlawt-1.8-bionic.jar` — full jar, `AWTContext.loadNatives()` returns OK under Termux openjdk-21.
- `$HOME/rlawt-test/MiniAwtContext.class` — compiled smoke test.

**Critical knowledge — carry forward**:
- Task 21 is DONE. Do not re-examine the rlawt build or propose patchelf workarounds.
- Bionic dlopen does NOT honor `-Djava.library.path` for resolving NEEDED libs. Must use `LD_LIBRARY_PATH` in the launcher env. Minimum required:
  ```
  LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib:/data/data/com.termux/files/usr/lib
  ```
- Target ELF spec for librlawt.so was met: NEEDED `libjawt.so libGL.so.1 libGLX.so.0 libX11.so libm.so libdl.so libc.so`; SONAME `librlawt.so`; single VERNEED entry references `libc.so` with Bionic-internal `LIBC` version (not glibc).
- All 26 undefined symbols verified present on device via `third_party/rlawt-bionic/audit-symbols.sh` (uses `/system/bin/readelf` — Termux's binutils package does NOT ship readelf; Android built-in is the only option that works out of run-as).
- `adb push` from Git-Bash mangles Unix paths — prefix the command with `MSYS2_ARG_CONV_EXCL='*'` to preserve `/sdcard/...` etc.

**Phase 2.1 concrete next steps**:
1. Draft `runelite-tablet/app/src/main/assets/scripts/launch-runelite-native.sh` adjacent to `launch-runelite.sh`. Gated by `RLT_NATIVE_TERMUX=1` env flag. Skips `proot-distro login`.
2. Resolve U4 — can the RuneLite bootstrap JARs (under `~/.runelite/repository2/`) be reached from Termux-native filesystem view? Currently they live inside proot's Ubuntu rootfs at `$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/root/.runelite/repository2/`. Either (a) copy/link them to a Termux-native-reachable path, or (b) bootstrap a fresh RuneLite install under Termux's filesystem. Pick based on size/simplicity.
3. Wire `RuneLiteSessionService` to select native vs proot path (Phase 2.2). This includes fixing the `ScriptManager.scriptsDeployed` re-deploy bug (memory `project_script_redeploy_vs_stale_apk.md`) so a new `launch-runelite-native.sh` asset actually reaches Termux on APK install.
4. A/B FPS at Varrock East Bank (Phase 3): baseline vs `RLT_NATIVE_TERMUX=1`.

**Task list snapshot (S78-end)**:
- Completed: 6-13, 11, 12, sampler tasks 7-9, **21** (rlawt rebuild — DONE)
- Deferred: 10 (bake sampler into PERF_MONITOR)
- Active / next: Phase 2.1 (native launch script)
- Pending: Phase 2.2, Phase 3, tasks 14-19
- Fallback (still): 20 (fork proot, patch seccomp — not needed as long as Phase 2.1 ships)

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

# Session State

**Last Updated**: 2026-04-18 | **Session**: 80 (per-phase GPU telemetry shipped + first real readings — GPU-bound confirmed, virgl driver has no GL_ARB_timer_query, JVM stuck on little cores 0-3)

## Current Phase
- **Phase**: `spike/direct-android-surface` — Option B native-Termux JVM with GPU plugin active. S80 landed the measurement layer: rlawt A1-A8 in-process (swap/gap/xsync/finish timers, GL query ring, capability probe), `perf-sampler.sh` + `fps-log-tail.sh` side-cars, Mali sysfs GPU-busy probe, env gating on `RLT_PERF_SAMPLE`/`RLAWT_PERF*`. First run data captured on R52X90378YB at Varrock East Bank: **31 FPS FpsPlugin overlay with probes ON (Uncapped)**, `gap_us mean≈18500` = 54 FPS swap ceiling, `finish_us mean≈8000` = GPU busy 8ms after swap returns (GPU-bound confirmed), `xsync_us mean≈250` = X IPC trivial, `glerr=0`, `mkcur=0`.
- **Status**: Per-phase telemetry complete and collecting clean data. **Bottleneck now triangulated to TWO places**: (1) Mali GPU saturation — `finish_us`=8ms is half the 60Hz frame budget; virgl Mesa 25.2.8 exports NO `GL_ARB_timer_query` (ext=0 ver=4.3) so we can't get GPU-side per-stage breakdowns, only CPU-side finish-proxy. (2) Client thread `cpus=0-3` — JVM is on little cores. S76 refuted cpuset-as-FPS-gate under software rendering, but under GPU rendering the workload profile is different (render thread now CPU-feeds GPU) and the little-core clamp is worth re-testing. TermuxProcessPin from S75 apparently isn't engaging or the foreground-service bind window is being missed on this launch path. Client thread ctxt switches = 1100/s vs S77 proot-era 3945/s — native path delivered ~4× reduction, proof proot ptrace removal helped.

## HOT CONTEXT — Resume Here

### ENTRY POINT FOR SESSION 81

**Next session goal**: Close the gap between 31 FPS FpsPlugin reading and 54 FPS swap ceiling. Run probes-OFF A/B first (gets us a clean FPS number without the 8ms glFinish probe overhead), then re-test big-core pinning now that pipeline is GPU+CPU bound (different profile from S76's CPU-only software case).

**Recommended opening moves for S81**:
1. **Probes-OFF A/B** — edit `LaunchCoordinator.kt:180` to drop `RLAWT_PERF_FINISH=1 RLAWT_PERF_XSYNC=1 RLAWT_PERF_GPU_TIMER=1` from the `cmdLine` (keep `RLAWT_PERF=1 RLT_PERF_SAMPLE=1` for the window summary + thread sampling). Rebuild, install, re-launch, read FpsPlugin overlay at Varrock East Bank. Expected: FPS rises noticeably since we stop blocking the render thread for glFinish after every swap.
2. **Big-core re-test** — inspect `TermuxProcessPin` and confirm whether it's firing at all on the native-Termux launch path. The S75/S76 scheduler work targeted `launch-runelite.sh` (proot path); `launch-runelite-native.sh` (native path) may not get the same foreground-service bind. Perf sampler shows Client thread `cpus=0-3` on S80, meaning we're in `/background` or `/moderate` cpuset, not `/top-app`. Either re-wire the foreground-service bind to this launch path OR use explicit `taskset 0xF0` in the launcher for the JVM.
3. **Fix the launcher's stale-lock check** (issue #55) — first thing tripping users when switching runs. `kill -0` alone is not enough under Android PID recycling. Verify `/proc/$pid/cmdline` contains `launch-runelite-native` before treating lock as live.
4. **Investigate the 31→54 gap** — if probes-off pushes FPS to 50+, the gap was 100% probe overhead and we're GPU-bound with little-core throttling. If FPS stays at 31, the 54 Hz rlawt cadence is swapping duplicate frames and Client isn't producing draws fast enough (independent of probes); dig into Client thread wait states via `/proc/$tid/wchan`.

### S80 HERO CHANGE — Per-Phase GPU Telemetry

**Shipped artifacts**:
- `third_party/rlawt/rlawt.h` — added `RlawtPerfState` typedef: flags, window size, rolling heap buffers (swap/gap/xsync/finish/gpu, 5×uint64_t arrays of size up to 10000), GL timer query ring, 5 GL function-pointer typedefs (portable self-declared), `RLAWT_PERF_MAX_WINDOW` constant, `perf` field on AWTContext's Unix block.
- `third_party/rlawt/rlawt_nix.c` — ~500 lines added: `rlawt_ts_delta_us`, `rlawt_compute_stats` (mean/p50/p95/max with validity mask), `rlawt_gl_has_ext` (word-boundary safe), capability probe block at context creation (GL_VENDOR/RENDERER/VERSION/glXIsDirect/extensions), A3 timer-query detection + version gate + dlsym ring, instrumented `swapBuffers` fast-path when disabled + per-phase timed path with optional XSync pre + lagged GPU-query readback + glFinish probe + pre/post swap timing + inter-swap gap + window-boundary WINDOW emit with composite stats line, `rlawtContextFreePlatform` NULL-safe cleanup of GL queries + all heap buffers.
- `app/src/main/assets/scripts/perf-sampler.sh` — new, ~146 lines. `/proc/$PID/task/*` per-thread snapshot (comm/state/cpus/vol/nonvol/schedstat) every INTERVAL_SEC with START/TICK/END labeling + GPU_BUSY sysfs read per tick. Gated on dual-PID liveness. PID file for trap-based cleanup.
- `app/src/main/assets/scripts/fps-log-tail.sh` — new, ~142 lines. `tail -F client.log` through a FIFO with persistent fd 3 (`exec 3< "$_fifo"`) to avoid per-iteration SIGPIPE on tail writer. Extracts first digits from FpsPlugin/`fps=`/`FPS:` patterns. 5s JVM-liveness watchdog.
- `app/src/main/assets/scripts/launch-runelite-native.sh` — added `PERF_SAMPLER_PID`/`FPS_TAIL_PID` trap slots, DIAG Mali GPU busy probe across 5 sysfs paths exporting `GPU_BUSY_PATH`, gated perf-sampler + fps-log-tail spawn post JVM launch + affinity taskset, cleanup_on_exit kills both side-cars.
- `app/src/main/java/com/runelitetablet/setup/ScriptManager.kt` — added `perf-sampler.sh`, `fps-log-tail.sh` to `SCRIPT_NAMES` between `libbionic-compat.c` and `update-runelite.sh`.
- `app/src/main/java/com/runelitetablet/setup/LaunchCoordinator.kt:180` — S80 late edit: added `RLT_PERF_SAMPLE=1 RLAWT_PERF=1 RLAWT_PERF_FINISH=1 RLAWT_PERF_GPU_TIMER=1 RLAWT_PERF_XSYNC=1` to the cmdLine env prefix so the flags actually engage.
- `app/libs/rlawt-1.8-bionic.jar` + `app/src/main/assets/libs/rlawt-1.8-bionic.jar` — repacked via Python zipfile (Git-Bash lacks `zip`) with the 113040-byte rebuilt librlawt.so.

**First-run numbers (R52X90378YB, Varrock East Bank, GPU on, Uncapped, probes ON)**:
```
gap_us    mean≈18500 p50≈18700 p95≈22000 max≈30000   → 54 FPS swap cadence
swap_us   mean≈15200 p50≈15000 p95≈18000             → 15ms per eglSwapBuffers
finish_us mean≈8000  p50≈7900                         → GPU busy 8ms AFTER swap returns
xsync_us  mean≈250   p50≈210                          → X IPC trivial, not a bottleneck
mkcur=0  glerr=0
FpsPlugin overlay: 31 FPS
Client thread: cpus=0-3 (little cores, NOT /top-app), ~1100 ctxt-switches/s
GL_ARB_timer_query: unavailable on virgl Mesa 25.2.8 (ext=0 ver=4.3)
glXIsDirect: 1
```

Theoretical probes-OFF gap: `18500 - 8000 - 250 ≈ 10250us → ~97 FPS swap ceiling`. Observed FpsPlugin 31 FPS suggests RL's Client thread isn't feeding draws fast enough to saturate the swap path — either throttled by the little-core clamp, or some other wait upstream of rlawt's swap.

### S79 → S80 CARRY-FORWARD (unchanged)

**LWJGL Bionic compat layer** stays shipped and stable. `patch-lwjgl-bionic.sh` + `libbionic-compat.c` + `libbionic-compat.so` continue to run at every launch. Three layers: `patchelf --replace-needed` rewrites glibc sonames in DT_NEEDED AND DT_VERNEED in one pass; `patchelf --clear-symbol-version` on 83 imports; `libbionic-compat.so` shims `__errno_location`, `__xstat64`, `__fxstat64`, `__getdelim`. GPU plugin activation gated on this layer — verified running on R52X90378YB.

**Mesa 25.2.8** downgrade persists — rebuilt via Docker per `docs/build-notes/mesa-25-build-patches.md` Patches 3+4. Deploy script handles Git-Bash path mangling via `cygpath -w`. Note: this Mesa version does NOT export `GL_ARB_timer_query` on virgl. Upgrading or side-loading timer-query support would unlock GPU-side per-stage breakdowns.

**HybridX11HostActivity binder_died escape hatch** is live — 3s sustained binder death → `finish()` returns user to RLT main. Fired cleanly in S80 when the stuck launcher (from stale lock) never connected.

**RL-CONFIG native patches** `gameSize`, `resize=RESIZE_WINDOW`, `stretched=100`. The `RESIZE_WINDOW` value is an invalid ExpandResizeType enum — RL logs `IllegalArgumentException: No enum constant` and silently falls back. **Filed as issue #56.** gameSize + stretched still apply; this is cosmetic.

**Critical knowledge — carry forward**:
- Task 21 DONE. Phase 2.1 DONE. Phase 2.2 DONE. Phase 3 GPU activation DONE. Do not re-examine rlawt build, launcher script, classpath assembly, LWJGL patching, or Mesa downgrade.
- Bionic dlopen does NOT honor `-Djava.library.path`; `LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib:/data/data/com.termux/files/usr/lib` is set in the launcher env.
- `adb push` from Git-Bash mangles Unix paths — prefix with `MSYS2_ARG_CONV_EXCL='*'`.
- `run-as com.termux` does NOT inherit Termux env — on-device ad-hoc commands must set HOME/PREFIX/TMPDIR explicitly.
- Launcher stale-lock check uses `kill -0 $pid` which false-positives under Android PID recycling (S80 tripped this — PID 32578 from prior launcher recycled by virgl_test_server_android, blocked every subsequent Launch tap). **Filed as issue #55.**
- GPU plugin Hybrid scaling + `-Dsun.java2d.uiScale=2` don't compose cleanly — main canvas scales, Swing sidebar doesn't. Cosmetic, **filed as issue #57.**

**Remaining Phase 3 / follow-up work (after S80)**:
1. **Probes-OFF A/B + GPU-mode FPS close** — NEW, S80 end. Primary S81 work stream.
2. **Big-core pinning on native launch path** — S75/S76 TermuxProcessPin was written for proot path; verify it fires on native path or add explicit `taskset 0xF0` to the JVM invocation.
3. **Issue #55 (P2)** — harden stale-lock check with `/proc/$pid/cmdline` verification.
4. **Issue #56 (P3)** — fix `RESIZE_WINDOW` → correct ExpandResizeType value.
5. **Issue #57 (P3)** — sidebar scaling under GPU Hybrid + uiScale=2.
6. **User-facing `useNativeTermux` toggle (task D5 / #19)** — today the pref is flipped via `adb shell` XML write. Add a SettingsScreen checkbox.
7. **Remove `scripts/native-launcher-wrapper.sh` (task D4 / #18)** — ad-hoc Phase 2.1 test wrapper, superseded.
8. **Update 120-FPS plan doc (task #23)** — reflect Option B path landed, GPU plugin active, S80 telemetry layer in place, next focus is probes-off A/B + big-core re-test.
9. **Close Review Notes R3 in Phase 2.2/3 spec (task D2 / #16)** — evidence now exists.

**Open issues carried forward + filed S80**:
- `#6` — P1, `security(auth)`: shellEscape missing `!` and does not strip CR/NL/NUL.
- `#7` — P2, `needs-repro`: UnsafeHelper abstract-class allocation.
- `#25` — P2, `needs-repro`: MESA_GL_VERSION_OVERRIDE does not unlock LWJGL OpenGL45.
- `#43` — P2, `needs-repro`: SetupOrchestrator isSuccess marker.
- `#55` — P2 (NEW S80), `fix(scripts)`: harden stale-lock check against PID reuse.
- `#56` — P3 (NEW S80), `fix(scripts)`: RESIZE_WINDOW is invalid ExpandResizeType enum.
- `#57` — P3 (NEW S80), `fix(ui)`: GPU Hybrid + sun.java2d.uiScale don't compose on Swing sidebar.

### Cpuset topology on R52X90378YB (verified S76, unchanged)

| cpuset | cpus | readable as shell |
|---|---|---|
| `/` (root) | 0-7 | read-only |
| `/top-app` | 0-7 (processes inside it show Cpus_allowed=0xff) | NO — empty read |
| `/foreground` | 0-6 | yes |
| `/background` | **0-3** | yes |
| `/moderate` | **0-3** (same mask as background) | yes |

S80 observation: Client thread `cpus=0-3` matches /background or /moderate. NOT /top-app (0-7). Foreground-service bind from TermuxProcessPin either isn't firing on the native-Termux launch path or is missing the bind window.

### Deployed artifacts on device (R52X90378YB, S80-end)

- RuneLiteTablet APK: rebuilt 22:58 EDT with LaunchCoordinator S80 edit that adds all five perf flags to the native-path `cmdLine`.
- `$HOME/scripts/launch-runelite-native.sh` — invokes patch-lwjgl-bionic.sh, DIAG GPU-busy probe, perf-sampler + fps-log-tail spawn when `RLT_PERF_SAMPLE=1`.
- `$HOME/scripts/perf-sampler.sh` + `$HOME/scripts/fps-log-tail.sh` — NEW S80 — deployed by ScriptManager.
- `$HOME/scripts/patch-lwjgl-bionic.sh` + `$HOME/scripts/libbionic-compat.c` (S79) — unchanged.
- `$PREFIX/lib/libbionic-compat.so` — compiled on-device, unchanged.
- `$HOME/.rlt/rlawt-1.8-bionic.jar` — auto-deployed by ScriptManager with the S80 telemetry-instrumented librlawt.so inside.
- `$HOME/.rlt/deployed-version` — current APK VERSION_CODE (S80).
- Telemetry logs captured S80: `$HOME/runelite-native.log` (344KB, rlawt [rlawt-perf] + [rlawt-info] + DIAG + RL-CONFIG), `$HOME/runelite-native-perf.log` (100KB, TICK snapshots 1/sec), `$HOME/fps-tail.log` (961B, FpsPlugin-line-grep output).
- Termux packages stable: fontconfig 2.17.1, ttf-dejavu 2.37, clang (REQUIRED), patchelf, binutils, unzip, coreutils. Mesa 25.2.8.

### Discovered-but-not-fixed bugs (carried forward)

- **ScriptManager re-deploy bug** — resolved in S78 Phase 2.2. Version marker at `$HOME/.rlt/deployed-version` vs `BuildConfig.VERSION_CODE`.
- **Deploy-both-APKs**: `com.termux.x11` is a separately-installed fork APK needing its own rebuild when Xlorie C code changes. `scripts/deploy-native-to-device.sh` handles both.
- **Proot seccomp-bpf fails on Samsung OneUI kernel** — historical; native path bypasses proot entirely.
- **rlawt bundled linux-aarch64/librlawt.so is glibc-linked** — resolved S78 Task 21.
- **S80 NEW**: Launcher stale-lock false-positive under PID recycling (issue #55).
- **S80 NEW**: `RESIZE_WINDOW` is not a valid RuneLite `ExpandResizeType` enum (issue #56).
- **S80 NEW**: Sidebar UI scale inconsistency under Hybrid + uiScale=2 (issue #57).

**Convention still enforced**: every commit must be `type(scope): subject` + narrative body + `Reason:` trailer (see `scripts/git/commit-msg`). File defects via `tools/create-issue.ps1` — do NOT write `.claude/defects/*`.

## Blockers

**1. GPU-mode FPS regression vs software baseline — PARTIALLY QUANTIFIED** (S79 end, updated S80).
- S80 telemetry landed and collected data. GPU plugin runs at `Plugin GpuPlugin is now running` + `Using device: virgl (Mali-G720-Immortalis MC12)` + `Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8`.
- Current reading: **31 FPS FpsPlugin with probes ON, Uncapped**. Software baseline historical was **12-15 FPS under proot** (S76). Native path delivered a clear speedup (proot removed, Client ctxt-switch rate dropped ~4×).
- Bottlenecks triangulated: (1) GPU saturation — `finish_us≈8ms` half the 60Hz budget; (2) little-core clamp — Client thread `cpus=0-3`; (3) probe overhead — ~44% of frame time is diagnostic-barrier cost.
- Unblocks when: probes-OFF A/B gives us a clean GPU-on FPS, and we've re-engaged big-core affinity on the native path to separate "GPU is at its limit" from "JVM is artificially throttled".

**Stale blockers removed**: proot ptrace (resolved S78), rlawt glibc (resolved S78 T21), background cpuset (resolved/refuted S76), Termux Mesa GLX handshake (resolved/irrelevant S79), LWJGL glibc native lib (resolved S79), RL AWT frame size (resolved S79 E1+E2).

## Recent Sessions

### Session 80 (2026-04-18)
**Work**: Shipped full per-phase GPU telemetry stack end-to-end in one rebuild cycle. `rlawt_nix.c` + `rlawt.h` A1-A8 in-process instrumentation (per-swap timing, inter-swap gap, XSync probe, lagged GPU timer-query ring, glFinish probe, window-summary stats with p50/p95/max, capability probe block, env-gated fast-disabled path). Side-car `perf-sampler.sh` for `/proc/$PID/task/*` thread snapshots + Mali sysfs GPU-busy counter. Side-car `fps-log-tail.sh` with FIFO-fd-persistence fix that I caught + corrected (agent's initial per-iteration reopen would SIGPIPE the tail writer). Launcher wired with PID traps + DIAG GPU-busy path probe. `ScriptManager.kt` added both side-car scripts to `SCRIPT_NAMES`. Late-session edit to `LaunchCoordinator.kt:180` added all five perf env flags to the dispatched cmdLine so the telemetry actually engages. rlawt jars repacked via Python zipfile. APK built green in 2s (incremental Kotlin). Installed R52X90378YB 22:58. Stale-lock bug surfaced: prior launcher PID 32578 was recycled by virgl daemon, blocked re-launch until manual process kill. User tapped Launch, logged in, hit Varrock East Bank.
**Decisions**: Collect first, diagnose second — no perf changes proposed until data in hand. Probes-ON run was correct first call despite known overhead, because `finish_us` is the only GPU-cost proxy we have without `GL_ARB_timer_query` (virgl Mesa 25.2.8 doesn't export it).
**Findings**: GPU-bound (finish_us≈8ms = 50% of 60Hz budget); X IPC not a bottleneck (xsync≈250us); JVM on little cores 0-3 (NOT /top-app — TermuxProcessPin not firing on native path); Client ctxt-switch rate 1100/s vs S77 proot 3945/s (~4× reduction from native path); FpsPlugin 31 FPS with probes ON vs ~97 FPS theoretical swap ceiling without probes. 3 defects filed as issues #55/#56/#57. Right sidebar UI scaling is dense (issue #57); RL-CONFIG `RESIZE_WINDOW` is invalid enum (#56); launcher stale-lock check false-positives under PID reuse (#55).
**Next**: S81 = probes-OFF A/B first (edit LaunchCoordinator to drop FINISH/XSYNC/GPU_TIMER flags, rebuild, re-measure FpsPlugin overlay), then re-engage big-core affinity for native path (either TermuxProcessPin rewiring or explicit taskset in the launcher).

### Session 79 (2026-04-17)
**Work**: LWJGL `liblwjgl.so` Bionic-compat unblock (the breakthrough) — solved the Phase 3 GPU-plugin blocker that had been open since S78. Fix is a three-layer `patch-lwjgl-bionic.sh` asset script + on-device-compiled `libbionic-compat.so` shim. Layer 1: `patchelf --replace-needed` rewrites all 4 glibc sonames (`libpthread.so.0 → libc.so`, `libc.so.6 → libc.so`, `libdl.so.2 → libdl.so`, `ld-linux-aarch64.so.1 → libc.so`) — confirmed empirically via on-device readelf diff that `--replace-needed` also updates the matching DT_VERNEED File entries in one pass. Layer 2: bulk `patchelf --clear-symbol-version` on all 83 imported symbols that carried `@GLIBC_2.17`. Layer 3: `libbionic-compat.c` ships as an APK asset and is compiled on first launch by Termux clang; exports the 4 glibc-only symbols (`__errno_location` → `__errno`, `__xstat64 → stat`, `__fxstat64 → fstat`, `__getdelim → getdelim`). Script runs unconditionally each launch — idempotent, ~1-2s overhead, self-heals from RL auto-updater or ScriptManager redeploying stale assets.
Also wired in S79: HybridX11HostActivity `binder_died` 3s escape hatch. ScriptManager `SCRIPT_NAMES` gained both the patch script and the .c file. launch-runelite-native.sh invokes the patch between preflight and JVM spawn. rlawt's `rlawt_nix.c` gained a `RLAWT_LOG` macro + per-fbconfig GLX_VISUAL_ID dump. Mesa 25.2.8 downgrade completed (Patch 3 dropped Mesa-26-specific patches; Patch 4 documented Windows-Docker bind-mount perms fix). New diagnostic `scripts/glx-fbconfig-probe.c`.
**Decisions / findings**: `patchelf --replace-needed` updates BOTH DT_NEEDED AND DT_VERNEED File entries (empirical). Bionic's strict version-match rejects OBJECT (data) symbols per-symbol lazy — clearing all 83 imports is safer than peeling layers. Symlink shim for ld-linux-aarch64.so.1 FAILED — Bionic checks DT_SONAME of loaded DSO, not requested NEEDED name. Mesa 25.2.8 downgrade turned out NOT to be the unblock — kept as toolchain. JNA / Discord RPC has same pattern, non-blocking; future cleanup. FPS regressed vs software baseline — unquantified at S79 end; captured as S80 entry point.
**Next** (S80): quantify + close the GPU-mode FPS regression. Seed per-phase telemetry — DONE in S80.

### Session 78 (2026-04-17)
**Work**: 4 commits: (1) Task 21 rebuild rlawt for Bionic via Android NDK — vendored upstream rlawt v1.8, built Bionic aarch64 librlawt.so, repackaged rlawt-1.8-bionic.jar. (2) Phase 2.1 `scripts(native): add launch-runelite-native.sh` — new launcher gated by `RLT_NATIVE_TERMUX=1`, skips proot-distro, regenerates classpath live, swaps Bionic rlawt jar. (3) Phase 2.2 `feat(scripts): wire native-Termux launch path via LaunchPreferences` — Kotlin `ui/LaunchPreferences.useNativeTermux` + APK-version-aware ScriptManager cache + `deployJars()` via `unzip -p "$APK" assets/libs/…` (141ms) + adaptive UI scale. (4) Logging expansion — DIAG preflight, WINDOW callback logs, RL-CONFIG profile patching for gameSize+stretched.
**Decisions / findings**: Task 21 spec gates loosened (VERNEED references Bionic sonames is fine). Jar deploy via APK unzip avoids Binder parcel limits. UI scale computed in Kotlin from displayMetrics, bucketed 1/1.5/2/2.5. GPU plugin blocker root-caused to Termux Mesa (but then S79 proved it was LWJGL, not Mesa). Immersive system bars fix shipped.
**Next**: Session 79 — LWJGL .so strace+patching, GLX fbconfig probe A/B. Don't restart guessing at env vars.

### Session 77 (2026-04-17)
**Work**: `scripts/jvm-wait-sampler.sh` + `scripts/jvm-wait-analyze.py`. 60s /proc capture proved `Client` thread has 3945 nonvol-ctxt-switches/s — synchronous ptrace interception is the FPS bottleneck. A/B'd `RLT_PROOT_SECCOMP=1`: Samsung OneUI kernel doesn't route SECCOMP_RET_TRACE → execve ENOSYS → reverted. Chose Option B (native-Termux JVM). Confirmed rlawt's bundled librlawt.so is glibc-linked; clean fix = NDK rebuild (Task 21).
**Decisions**: Proot ptrace is the confirmed bottleneck. Option B over Option A. Two durable memories added. Three new analysis docs under `runelite-tablet/docs/logs/`.
**Next**: S78 = execute Task 21. Then unblock Phases 1.3 → 2.1 → 2.2 → Phase 3.

### Session 76 (2026-04-17)
**Work**: Re-launched RLT with S75's `TermuxProcessPin` Path A after recovering from the ScriptManager re-deploy bug. Attempt 3: `cpuset=/top-app` on all markers, JVM main + virgl `Cpus_allowed_list: 4-7`, JVM sub-threads `0-7` — perfect scheduler state. 60s FpsPlugin capture: **12-15 FPS** (S74 baseline was 13). Zero meaningful gain. Per-core CPU delta under gameplay: ~27% utilization across all 8 cores — system 70%+ idle.
**Decisions**: Path A **refuted** as an FPS fix under software rendering. Memory `project_cpuset_is_not_the_fps_bottleneck.md` added. S80 NOTE: GPU-rendering profile is different — worth re-testing big cores under GPU load.
**Next**: S77 profiles with `/proc/$JVM_PID/schedstat` to find the real sink.

## Active Plans

- **Phase 9: Comprehensive Logging System** — **COMPLETE + DEVICE-VERIFIED**. 127/128 spec items. 210 tests. All layers verified on device.
- **Clean Architecture Refactor (Phases 1-8)** — **COMPLETE**.
- **Presentation Pipeline 120 FPS** — **IN PROGRESS** (`.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`). Option B native-Termux JVM operational with GPU plugin (S79) + full per-phase telemetry landed (S80). Next: close GPU-mode FPS gap via probes-off A/B + big-core pinning re-test on native path. Plan doc still lists superseded slice sequence; update pending.

## Reference
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Test code**: `runelite-tablet/app/src/test/java/com/runelitetablet/`
- **Native code**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/`, `third_party/rlawt/rlawt_nix.c`, `third_party/rlawt/rlawt.h`
- **Debug docs**: `runelite-tablet/docs/debug-logging.md`
- **S80 screenshot**: `runelite-tablet/rlt-s80-gpuon.png` (Varrock East Bank, GPU on, probes ON, FpsPlugin=31, sidebar scaling issue visible)
- **Build notes**: `docs/build-notes/mesa-25-build-patches.md`
- **FPS diagnosis (session 77)**: `runelite-tablet/docs/logs/slice5-jvm-wait-analysis.md`, `slice5-seccomp-ab.md`, `slice5-option-b-rlawt-blocker.md`

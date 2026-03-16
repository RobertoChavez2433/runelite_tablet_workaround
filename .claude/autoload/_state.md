# Session State

**Last Updated**: 2026-03-15 | **Session**: 60

## Current Phase
- **Phase**: Direct Android Surface Spike — Direct-JVM Patch Vehicle
- **Status**: `internal-hybrid` now has a working real `direct-jvm` RuneLite launch path plus a classpath-override hook for patched jars. A fresh launcher-vs-direct comparison still stayed in the same broad sustained band, so bypassing the RuneLite launcher bootstrap does not unlock `120 FPS`. The branch is now prepared for targeted client-side jar experiments against the RuneLite GPU upload/synchronization path.

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 60: Made `direct-jvm` a working real-RuneLite launch mode, fixed the harness so appended launch logs no longer cause false positives, fixed CRLF corruption in seeded direct classpaths, and validated a fresh launcher-vs-direct comparison. The result did not unlock a new regime: launcher still averaged about `38.6 FPS` visible-surface and direct-jvm about `43.7 FPS`. The branch now has a clean classpath-override hook for patched jars, and pulled jar inspection confirmed the concrete next patch target is `GpuPlugin.prepareInterfaceTexture(...)` / `drawUi(...)` in `client-1.12.20.jar`.**

Key findings this session:
1. **Bypassing the RuneLite launcher is now real, not hypothetical** — `direct-jvm` reaches real RuneLite with the GPU plugin on VirGL.
2. **Launcher ownership is not the missing unlock** — the fresh launcher-vs-direct comparison stayed in the same broad `40-ish FPS` band, far below `120 FPS`.
3. **The branch now has a patch vehicle** — `launch-runelite.sh` can substitute jars from `/root/.runelite/repository2-overrides` during `direct-jvm` launches.
4. **The next patch target is concrete** — pulled jar inspection and bytecode dump confirmed the UI upload path in `GpuPlugin.prepareInterfaceTexture(...)` and `drawUi(...)`.
5. **The old synthetic-control invalidation is no longer the main resume point** — the branch is past harness bring-up and into client-side patch territory.

### What Needs to Happen Next

1. **P0: Use the new direct-jvm override path to run a patched client jar** — the next meaningful test is no longer launcher/path plumbing.
2. **P0: Patch the RuneLite GPU client path first** — start with the `GpuPlugin.prepareInterfaceTexture(...)` / `drawUi(...)` UI upload path.
3. **P1: Keep hybrid input semantics locked** — do not regress into direct-touch game injection. Trackpad-style touch only; real mouse stays real mouse.

## Blockers

**1. Real RuneLite sustained FPS is still well below target on the working shared X11/VirGL path** — Stock, external hybrid, `internal-hybrid`, and now `internal-hybrid` `direct-jvm` all stay in the same broad sustained band. Bypassing the launcher bootstrap did not unlock a credible path to sustained `120 FPS`.

**2. The ceiling still appears upstream of the native presenter** — Choreographer and redraw wakeups can hit `120 FPS`, but damage-triggered redraw cadence and renderer estimates stay much lower, which still points to client-side render / upload / synchronization behavior rather than Android presentation.

**3. The next optimization work has not been executed yet** — The branch now has a working direct-jvm patch vehicle and an override directory for patched jars, but no client-side jar experiment has been run yet against the identified GPU upload target.

## Recent Sessions

### Session 60 (2026-03-15)
**Work**: Added a working `direct-jvm` real RuneLite launch mode, wired it through the real evidence harness, fixed launch-state false positives caused by appended Termux logs, fixed CRLF corruption in seeded direct classpaths, and validated a fresh launcher-vs-direct comparison. Added a direct-classpath override directory so patched jars can be injected without mutating the launcher-managed repository, then pulled and disassembled the live RuneLite jars to confirm the next patch target in `GpuPlugin.prepareInterfaceTexture(...)` / `drawUi(...)`.
**Decisions**: The RuneLite launcher bootstrap is not the main steady-state limiter. Keep `direct-jvm` as the patch/testing vehicle, and move the next work to targeted client-side jar experiments rather than more launcher/X11 plumbing.
**Next**: Patch and override the relevant RuneLite GPU client path, starting with the interface upload / synchronization path in `client-1.12.20.jar`.

### Session 59 (2026-03-15)
**Work**: Added native timing and cadence diagnostics in the Lorie renderer path, forced RuneLite GPU settings (`unlockFps=true`, `fpsTarget=120`, `vsyncMode=OFF`), captured default-res, half-res, and `--scale 1` internal-hybrid evidence runs, and wired the combined probe harness so it can drive `internal-hybrid` synthetic controls. Also captured the first fullscreen `glxgears` synthetic control attempt, then invalidated it after confirming deploy and probe overlapped and Android force-stopped the app during install.
**Decisions**: The Android-present step is no longer the main suspect; the remaining ceiling is upstream in Linux/AWT/X11 frame production. Future synthetic control runs must be strictly sequential after deploy.
**Next**: Rerun the `internal-hybrid` synthetic control sequentially, then continue upstream client-path diagnostics. (Completed in Session 60; branch now has a direct-jvm patch vehicle.)

### Session 58 (2026-03-15)
**Work**: Debugged the in-app `CmdEntryPoint` route until `internal-hybrid` successfully booted real RuneLite. Added startup-log preservation in `launch-runelite.sh`, widened diagnosis with raw logcat, proved the donor `libXlorie.so` must be extracted from the installed `com.termux.x11` APK rather than loaded from `nativeLibraryDir`, fixed the extraction target so it writes into the Termux runtime filesystem instead of `ctx.getFilesDir()`, rebuilt and installed the app, and captured the first successful clean-start `internal-hybrid` real RuneLite runs at default-res and half-res.
**Decisions**: The in-app bootstrap route is now a real measurement path, not just a spike. But the first working captures still land in essentially the same sustained FPS envelope as the external hybrid path, so bootstrap ownership alone is not the missing unlock.
**Next**: Compare `internal-hybrid` against the external hybrid path as a runtime architecture question and then pivot toward deeper presentation-path changes if the envelope still does not move.

### Session 57 (2026-03-15)
**Work**: Started the first in-app Java-side X server experiment. Added `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java`, introduced an `internal-hybrid` presentation variant in `launch-runelite.sh`, updated the real RuneLite evidence harness to drive that variant, built and installed the app, and captured the first clean-start `internal-hybrid` real RuneLite run. The run failed before X11 socket creation, with repeated `Waiting for X11 socket...` retries and no surviving `app_process` / `CmdEntryPoint` server process visible in the dump-state snapshot.
**Decisions**: The working stock/hybrid X11 path is now just the baseline. The next useful work on this branch is to debug the in-app `CmdEntryPoint` boot path directly, because that is the first app-owned server route with a realistic chance of escaping the current ceiling.
**Next**: Capture direct `app_process` stdout/stderr for the in-app `CmdEntryPoint` launch, fix the earliest boot failure until the X11 socket appears, and only then re-run real RuneLite on `internal-hybrid`.

### Session 56 (2026-03-15)
**Work**: Extended the real RuneLite evidence harness into a stock-vs-hybrid comparator on the same launcher path. Patched `launch-runelite.sh` and `HybridX11TestReceiver` so the real launcher can run `stock` or `hybrid`, added variant-aware visible-surface parsing plus average/median FPS summaries to `scripts/hybrid-x11-runelite-evidence.ps1`, and captured matching 30s clean-start real RuneLite runs for stock and hybrid. Then added a forced half-res GPU display override (`1480x924`) and validated that the override applied in the launch log before capturing a hybrid half-res run.
**Decisions**: The X11/VirGL bottleneck is no longer credibly hybrid-only. Stock and hybrid perform similarly enough that more parity-only testing is low value, and a half-res GPU display override does not materially raise the sustained FPS envelope.
**Next**: Pivot from comparator work to a path that can plausibly exceed the current X11/VirGL ceiling, either via deeper presentation redesign or a more aggressive app-owned/direct-surface route.

### Session 55 (2026-03-15)
**Work**: Added `scripts/hybrid-x11-clean-probe.ps1` and `scripts/hybrid-x11-runelite-evidence.ps1`. Validated clean-start stock/hybrid synthetic probes, corrected an invalid parallel probe attempt, and then captured the first clean-start real RuneLite evidence run on the hybrid host. Verified real RuneLite reaches `running`, GPU plugin is active on `virgl`, the visible hybrid surface is voted `120.00 Hz`, and sustained rendering is still mostly around the `50-65 FPS` range rather than `120 FPS`.
**Decisions**: The hybrid RuneLite evidence harness is now the default measurement method for top-level performance claims. Synthetic probes are still useful for quick isolation, but they no longer define success or failure by themselves.
**Next**: Use the real RuneLite evidence harness to drive the next optimization pass on the X11/VirGL presentation chain, or build a like-for-like stock real RuneLite comparator if needed.

### Session 54 (2026-03-15)
**Work**: Created and advanced the `spike/direct-android-surface` branch. Added a live hybrid iteration log, validated direct app-owned surface `120 fps`, proved `TERMUX_X11_OVERRIDE_PACKAGE` routing into our app, built a functioning hybrid host activity that launches real RuneLite with native VirGL, restored safe trackpad-style touch semantics, and ran multiple stock vs hybrid VirGL probes. Latest result before the clean-start harness work: hybrid split-start probes still collapse around `0.4 FPS`.
**Decisions**: Stay on the existing Linux RuneLite path. Use hybrid option `C` first. Touchscreen must remain trackpad-style only; no direct-touch injection. Real hardware mouse/touchpad stays real pointer input.
**Next**: Replace ad hoc probe runs with clean-start harnesses, then measure real RuneLite instead of relying only on synthetic clients.

### Session 53 (2026-03-12)
**Work**: Implemented VirGL fix pipeline via `/implement` (5/7 phases, 6 quality gates PASS). Modified 4 files: gl_test_log.h, gl_test_harness.c, run-tests.sh, launch-runelite.sh. Deployed to device via device-run.sh. On-device: SIGSEGV persists, ALL rendering BLACK. Phase 0+1+2 fixes insufficient.
**Decisions**: device-run.sh for adb deployment (bypasses Git Bash quoting). FBO creation ordering fix. mali-native GLSL=140, mali-angle GLSL=430. tr -d "\015" for CR stripping (not sed).
**Next**: Debug SIGSEGV (run modules individually). Debug BLACK rendering (trivial triangle test). Run FBO probe (--module 7).

### Session 52 (2026-03-11)
**Work**: Root cause analysis via 3 parallel Sonnet research agents. Plan written by Sonnet agent. Adversarial review by Opus (7 MUST-FIX, 9 SHOULD-CONSIDER). All fixes incorporated into approved 6-phase plan. No code changes — plan only.
**Decisions**: GL_DEPTH_CLAMP is highest-probability FBO fix. 4.3COMPAT+430 for version consistency. Renderbuffer instead of depth texture. GLFW_VISIBLE=TRUE required. Phase 2 deploys as single unit.
**Next**: Implement Phase 0 (SIGSEGV fix), Phase 1 (env fixes), Phase 2 (FBO code fixes). Deploy and test.

### Session 51 (2026-03-10)
**Work**: Committed test pipeline (14 files) + LD_LIBRARY_PATH fix. Systematic debugging: fixed GL_MAX_VIEWPORT_DIMS overflow, DEPTH_COMPONENT32F→24, UBO diagnostic logging. Deployed+rebuilt+ran on device. Critical finding: FBO rendering completely broken on VirGL (A=0 after glClear proves FBO never written to). SIGSEGV persists despite viewport dims fix.
**Decisions**: DEPTH_COMPONENT24 over 32F. MSYS_NO_PATHCONV=1 for all adb from Git Bash. Push individual files not directories. Invoke scripts via full bash path not shebangs.
**Next**: Test rendering to default framebuffer (bypass FBO). Isolate SIGSEGV by running module 1 individually. Redesign tests if FBO is fundamentally broken on VirGL.

### Session 50 (2026-03-10)
**Work**: Implemented VirGL test pipeline via `/implement` (Phases 1-6, 13 files, 6 quality gates PASS). Fixed 4 P2 nitpicks. Deployed to device. Fixed 3 deployment bugs (LD_LIBRARY_PATH, X11 locks, TMPDIR). First successful on-device run: VirGL+ANGLE works, both shims activate correctly. But all depth tests render BLACK — fix is mechanically correct but geometry not visible.
**Decisions**: `env -u LD_LIBRARY_PATH` for VirGL server (Termux OpenSSL conflicts). Always kill+clean X11 before starting (stale locks). Set TMPDIR/XDG_RUNTIME_DIR in self-bootstrap. GLFW_PLATFORM=x11 for GLFW 3.4.
**Next**: Debug BLACK rendering (likely test harness projection/geometry bug). Fix Modules 1-3 SIGSEGV. Apply LD_LIBRARY_PATH fix to launch-runelite.sh. Commit files.

## Active Plans

- **Direct Android Surface Spike** — **ACTIVE**. Session 60. Real evidence plus fresh launcher-vs-direct-jvm comparison now point upstream of launcher ownership and Android presentation. The branch is prepared for patched client-jar experiments.
- **VirGL Fix Pipeline** — **IMPLEMENTED, SUPERSEDED FOR CURRENT INVESTIGATION**. Session 53. Historical harness/debug work remains relevant, but current active branch focus is hybrid presentation.
- **Auth Session Refresh Fix** — **COMPLETE**. Session 45-46.
- **Lifecycle + GPU Acceleration** — **COMPLETE**. All 3 phases.
- **GeckoView Auth Integration** — **COMPLETE**.

## Reference
- **Hybrid iteration log**: `runelite-tablet/docs/hybrid-x11-iteration-log.md`
- **Clean synthetic probe harness**: `scripts/hybrid-x11-clean-probe.ps1`
- **Real RuneLite evidence harness**: `scripts/hybrid-x11-runelite-evidence.ps1`
- **Pragmatic route**: `runelite-tablet/docs/pragmatic-route.md`
- **Android stack map**: `runelite-tablet/docs/android-stack-map.md`
- **VirGL fix pipeline plan**: `.claude/plans/2026-03-11-virgl-fix-pipeline.md`
- **VirGL fix pipeline review**: `.claude/adversarial_reviews/2026-03-11-virgl-fix-pipeline/review.md`
- **VirGL test pipeline plan**: `.claude/plans/2026-03-09-virgl-test-pipeline.md`
- **VirGL test pipeline spec**: `.claude/specs/2026-03-09-virgl-test-pipeline-spec.md`
- **VirGL test pipeline review**: `.claude/adversarial_reviews/2026-03-09-virgl-test-pipeline/review.md`
- **VirGL capability dump**: `.claude/research/virgl-capabilities-dump.md`
- **Implement checkpoint**: `.claude/state/implement-checkpoint.json`
- **Auth session fix plan**: `.claude/plans/2026-03-08-auth-session-refresh-fix.md`
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **GL test source**: `runelite-tablet/gl-tests/` (15 files)
- **Research**: `.claude/research/` (9 files + README)
- **Device deploy script**: `runelite-tablet/gl-tests/scripts/device-run.sh`
- **Test results on device**: `~/gl-tests/results/run-20260311-212430/`
- **jcodemunch MCP**: `.mcp.json` configured (C:\Users\rseba\Projects\jcodemunch-mcp)

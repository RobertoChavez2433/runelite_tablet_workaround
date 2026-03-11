# Session State

**Last Updated**: 2026-03-10 | **Session**: 51

## Current Phase
- **Phase**: MVP Development — VirGL FBO Rendering Broken (Root Cause Found)
- **Status**: Systematic debugging revealed FBO rendering is completely non-functional on VirGL. Alpha=0 after `glClear(0,0,0,1)` proves `glClear`/`glReadPixels` don't operate on the FBO at all. This is NOT a depth issue — the entire FBO pipeline is broken. `--all` SIGSEGV persists despite `GL_MAX_VIEWPORT_DIMS` stack overflow fix — deeper crash source in `log_gl_caps()`. Two commits landed: test pipeline files (14 files) + LD_LIBRARY_PATH fix in launch-runelite.sh.

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 51: Systematic debugging of BLACK rendering + SIGSEGV. Committed test pipeline (14 files) and LD_LIBRARY_PATH fix to launch-runelite.sh. Applied 3 code fixes (GL_MAX_VIEWPORT_DIMS overflow, DEPTH_COMPONENT32F→24, UBO diagnostic logging). Deployed to device, rebuilt, ran tests. Critical finding: FBO rendering completely broken on VirGL — `glClear(0,0,0,1)` produces A=0, proving the FBO color attachment is never written to. SIGSEGV in --all mode persists despite viewport dims fix.**

Key findings this session:
1. **COMMITTED**: Test pipeline (14 files, 5203 insertions) + `env -u LD_LIBRARY_PATH` fix in launch-runelite.sh (2 separate commits)
2. **FBO IS THE ROOT CAUSE**: `Center pixel (128,64): R=0 G=0 B=0 A=0` — since `glClearColor(0,0,0,1)` sets alpha to 1.0, getting A=0 proves `glClear` never wrote to the FBO. The entire FBO color attachment is uninitialized zeros. This is NOT a depth issue.
3. **DEPTH_COMPONENT24 didn't help**: Switching from 32F to 24 made no difference — the problem is FBO fundamentals, not depth format
4. **SIGSEGV deeper than GL_MAX_VIEWPORT_DIMS**: Fixed the stack overflow (GLint→GLint[2]) but `--all` still crashes. Crash happens before any `[INFO]` output from the harness. Likely another query in `log_gl_caps()` or extension enumeration via `glGetStringi`
5. **Shims still work mechanically**: Shim A verified (glClipControl injected, glGetError=0x0000, state confirmed). Shim B intercepts correctly (GL_GREATER→GL_LESS, glClearDepth 0→1). The shims aren't the problem.
6. **adb push directory mode crashes** on Windows Git Bash with `std::bad_alloc` — must push individual files with `MSYS_NO_PATHCONV=1`
7. **#!/usr/bin/env bash shebangs** don't work in Termux `run-as` — must invoke via full path `/data/data/com.termux/files/usr/bin/bash script.sh`

### What Needs to Happen Next

1. **P0: Debug VirGL FBO non-functionality** — Render to DEFAULT framebuffer (not FBO) and verify geometry appears. If yes, VirGL's FBO implementation is broken. If no, rendering itself is broken. Also try `glGetError()` after every FBO operation to find which call actually fails.
2. **P0: Isolate --all SIGSEGV** — Run `--module 1` individually to see if Module 1 alone crashes. If it does, bisect the `log_gl_caps()` queries (remove extension enumeration, remove specific glGetIntegerv calls). If module 1 is fine alone, test `--module 2`, `--module 3` etc.
3. **P1: If FBO doesn't work on VirGL** — Redesign depth tests to render to the DEFAULT framebuffer + `glReadPixels` from it. FBO-based testing may be fundamentally incompatible with VirGL.

## Blockers

**1. VirGL FBO rendering completely broken** — `glClear` and `glDrawArrays` don't write to FBO color attachment (A=0 after glClear(0,0,0,1) = uninitialized zeros). All FBO-based tests produce BLACK. Root cause unknown — could be VirGL's FBO implementation, a Mesa virpipe bug, or an incompatible FBO configuration.

**2. --all SIGSEGV in log_gl_caps()** — Crashes before any module output. `GL_MAX_VIEWPORT_DIMS` stack overflow was fixed but crash persists. Likely another problematic `glGetIntegerv` query or `glGetStringi` extension enumeration crash.

## Recent Sessions

### Session 51 (2026-03-10)
**Work**: Committed test pipeline (14 files) + LD_LIBRARY_PATH fix. Systematic debugging: fixed GL_MAX_VIEWPORT_DIMS overflow, DEPTH_COMPONENT32F→24, UBO diagnostic logging. Deployed+rebuilt+ran on device. Critical finding: FBO rendering completely broken on VirGL (A=0 after glClear proves FBO never written to). SIGSEGV persists despite viewport dims fix.
**Decisions**: DEPTH_COMPONENT24 over 32F. MSYS_NO_PATHCONV=1 for all adb from Git Bash. Push individual files not directories. Invoke scripts via full bash path not shebangs.
**Next**: Test rendering to default framebuffer (bypass FBO). Isolate SIGSEGV by running module 1 individually. Redesign tests if FBO is fundamentally broken on VirGL.

### Session 50 (2026-03-10)
**Work**: Implemented VirGL test pipeline via `/implement` (Phases 1-6, 13 files, 6 quality gates PASS). Fixed 4 P2 nitpicks. Deployed to device. Fixed 3 deployment bugs (LD_LIBRARY_PATH, X11 locks, TMPDIR). First successful on-device run: VirGL+ANGLE works, both shims activate correctly. But all depth tests render BLACK — fix is mechanically correct but geometry not visible.
**Decisions**: `env -u LD_LIBRARY_PATH` for VirGL server (Termux OpenSSL conflicts). Always kill+clean X11 before starting (stale locks). Set TMPDIR/XDG_RUNTIME_DIR in self-bootstrap. GLFW_PLATFORM=x11 for GLFW 3.4.
**Next**: Debug BLACK rendering (likely test harness projection/geometry bug). Fix Modules 1-3 SIGSEGV. Apply LD_LIBRARY_PATH fix to launch-runelite.sh. Commit files.

### Session 49 (2026-03-09)
**Work**: Investigated GPU plugin black screen via 8 research agents. Root cause: reversed-Z depth buffer requires `glClipControl` (GL 4.5) which VirGL doesn't support. `GL_ARB_clip_control` confirmed absent from extension list. `MESA_NO_ERROR=1` masked the failure. Applied GLSL 330 override (shaders compile) and GL 4.5 override (didn't fix — LWJGL checks function pointers not just version string). Captured full VirGL capability dump. Designed 3-tier test pipeline via `/brainstorming` → `/adversarial-review` → `/writing-plans`. 6 MUST-FIX items found and addressed.
**Decisions**: LD_PRELOAD shim approach (not patching RuneLite). Standalone developer tool (not app integration). Sub-process LD_PRELOAD testing (not dlopen). Cross-UID deploy via staging to /data/local/tmp/. Environment allowlist for results (not dump-all).
**Next**: `/implement` test pipeline (7 phases). Deploy, run --quick, determine winning shim. Apply to launch-runelite.sh.

### Session 48 (2026-03-09)
**Work**: VirGL socket fix achieved via 4-agent research. 11 changes: `--shared-tmp`, socket wait with `[ -S ]`, `MESA_GLX_ALPHA_BITS=0` (24-bit visual fix), glxgears instead of glxinfo, stock Mesa replaces lfdevs, `termux-x11-preference` replaces xrandr (colon syntax), `com.termux.x11.Loader` cleanup pattern. VirGL confirmed working (virgl renderer detected). GPU plugin fails: GLSL 3.30 not supported.
**Decisions**: Stock Ubuntu Mesa for Mali (not lfdevs). Termux:X11 preferences for resolution (not xrandr). glxgears for virpipe detection (not glxinfo). MESA_GLX_ALPHA_BITS=0 for visual depth fix.
**Next**: Add MESA_GLSL_VERSION_OVERRIDE=330 (one-line). Test GPU plugin. Software fallback resolution.

### Session 47 (2026-03-09)
**Work**: Eliminated hardcoded versions (launcher 2.7.7, Mesa 26.1.0). Fixed RuneLite always-run-launcher (auto-update). Fixed JVM flag propagation (--scale 2, RUNELITE_VMARGS=-Xmx4g). Fixed lfdevs Mesa download URL (was 404). Fixed GPU marker staleness. Deployed v2→v5 across 4 iterations. lfdevs Mesa 26.1.0-devel now confirmed installed. VirGL still broken — socket path mismatch.
**Decisions**: Use RUNELITE_VMARGS (not JDK_JAVA_OPTIONS) for client JVM args. Use --scale 2 (launcher's native mechanism). Always delete GPU marker on setup-gpu run. Fallback Mesa version = mesa-26.1.0-devel-20260208 (actual existing tag).
**Next**: Fix VirGL socket path (P0). Lower xrandr resolution as interim perf mitigation. Test auth in-game.

## Active Plans

- **VirGL Test Pipeline** — **DEPLOYED, FBO BROKEN**. Session 51. 3 fixes applied (viewport overflow, depth format, UBO logging). On-device: compiles, shims activate, but FBO rendering completely non-functional (A=0 proves glClear doesn't write to FBO). Need to test default framebuffer rendering.
- **GLSL Version Override** — **APPLIED**. Session 49. `MESA_GLSL_VERSION_OVERRIDE=330` added. Shaders compile.
- **Mali GPU Acceleration** — **FBO BROKEN ON VIRGL**. VirGL connected, shaders compile, shims activate, but FBO doesn't work at all. May need to render to default framebuffer.
- **Auth Session Refresh Fix** — **COMPLETE**. Session 45-46.
- **Lifecycle + GPU Acceleration** — **COMPLETE**. All 3 phases.
- **GeckoView Auth Integration** — **COMPLETE**.

## Reference
- **VirGL test pipeline plan**: `.claude/plans/2026-03-09-virgl-test-pipeline.md`
- **VirGL test pipeline spec**: `.claude/specs/2026-03-09-virgl-test-pipeline-spec.md`
- **VirGL test pipeline review**: `.claude/adversarial_reviews/2026-03-09-virgl-test-pipeline/review.md`
- **VirGL capability dump**: `.claude/research/virgl-capabilities-dump.md`
- **Implement checkpoint**: `.claude/state/implement-checkpoint.json`
- **Auth session fix plan**: `.claude/plans/2026-03-08-auth-session-refresh-fix.md`
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **GL test source**: `runelite-tablet/gl-tests/` (14 files)
- **Research**: `.claude/research/` (9 files + README)
- **Perf logs on device**: `~/runelite/gc.log`, `~/runelite/perf-monitor.log`, `~/runelite-launch.log`
- **Test results on device**: `~/gl-tests/results/run-20260310-211044/`
- **jcodemunch MCP**: `.mcp.json` configured (C:\Users\rseba\Projects\jcodemunch-mcp)

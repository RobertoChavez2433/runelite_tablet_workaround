# Session State

**Last Updated**: 2026-03-12 | **Session**: 53

## Current Phase
- **Phase**: MVP Development — VirGL Fix Pipeline Implemented, On-Device Testing Failed
- **Status**: Implemented 5/7 phases of fix pipeline via `/implement` (all 6 quality gates PASS). Deployed to device and ran tests. SIGSEGV persists on `--all` mode (Phase 0 fix insufficient). ALL rendering still BLACK including default framebuffer fallback — not just FBO. Root causes hypothesized in session 52 did not resolve the issues. Need deeper investigation.

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 53: Implemented VirGL fix pipeline (5/7 phases, 6 gates PASS). Deployed to device via device-run.sh script. On-device results: SIGSEGV still crashes --all mode; ALL rendering BLACK (FBO and default framebuffer). Phase 0 query removals didn't fix SIGSEGV. MESA_EXTENSION_OVERRIDE + 4.3COMPAT + GLSL 430 didn't fix rendering. Both shims activate correctly but still BLACK.**

Key findings this session:
1. **SIGSEGV NOT fixed by Phase 0** — Removed 7 dangerous queries + added fflush fences + version-gated glGetStringi, but `--all` still crashes. The crash is NOT in the int_queries we removed — it's elsewhere (possibly extension enumeration, or running multiple modules sequentially triggers it).
2. **ALL rendering BLACK** — Not just FBO. Default framebuffer (GLFW_VISIBLE=TRUE, depth hints) also produces R=0 G=0 B=0 A=0. This means the rendering failure is upstream of FBO: geometry/projection/vertex data is likely never reaching the GPU.
3. **Shims activate correctly** — ClipControl shim: `glClipControl injected, glGetError=0x0000`. Depth flip shim: depth func/clear intercepted. But still BLACK — proving the issue is not depth-related.
4. **Module 4a/4b/4c individually**: All BLACK, depth min=max=mean=0.000. Zero geometry rendered.
5. **Deployment solved** — device-run.sh script bypasses Git Bash quoting issues. Push to /data/local/tmp/, run via `adb shell "run-as com.termux ... bash /data/local/tmp/device-run.sh"`.

### What Needs to Happen Next

1. **P0: Debug SIGSEGV** — Run `--module 1` individually to isolate crash. The crash may only happen when running ALL modules sequentially (resource leak between modules?). Check harness.log from --all run for last output before crash.
2. **P0: Debug BLACK rendering** — The geometry is never rendered on ANY backend config. Likely a harness bug: projection matrix, vertex data, or draw call setup is wrong. Test with a trivial triangle (no UBO, no matrices) to isolate.
3. **P1: Check if probe_fbo_capability() ran** — The --all crash prevented the FBO probe from executing. Run `--module 7` individually to get FBO diagnostic data.

## Blockers

**1. VirGL ALL rendering BLACK** — NOT just FBO. Default framebuffer also renders black. Geometry/vertex data never reaches GPU. Upstream of depth buffer, FBO, and shims. Need trivial triangle test to isolate.

**2. --all SIGSEGV persists** — Phase 0 query removals did NOT fix it. Crash happens before module output despite removing GL_MAX_VARYING_FLOATS etc. Root cause is elsewhere — possibly extension enumeration loop, or sequential module execution leaks resources.

## Recent Sessions

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

### Session 49 (2026-03-09)
**Work**: Investigated GPU plugin black screen via 8 research agents. Root cause: reversed-Z depth buffer requires `glClipControl` (GL 4.5) which VirGL doesn't support. `GL_ARB_clip_control` confirmed absent from extension list. `MESA_NO_ERROR=1` masked the failure. Applied GLSL 330 override (shaders compile) and GL 4.5 override (didn't fix — LWJGL checks function pointers not just version string). Captured full VirGL capability dump. Designed 3-tier test pipeline via `/brainstorming` → `/adversarial-review` → `/writing-plans`. 6 MUST-FIX items found and addressed.
**Decisions**: LD_PRELOAD shim approach (not patching RuneLite). Standalone developer tool (not app integration). Sub-process LD_PRELOAD testing (not dlopen). Cross-UID deploy via staging to /data/local/tmp/. Environment allowlist for results (not dump-all).
**Next**: `/implement` test pipeline (7 phases). Deploy, run --quick, determine winning shim. Apply to launch-runelite.sh.

## Active Plans

- **VirGL Fix Pipeline** — **IMPLEMENTED, TESTS FAILED**. Session 53. 5/7 phases done, 6 quality gates PASS. On-device: SIGSEGV persists, rendering BLACK. `.claude/plans/2026-03-11-virgl-fix-pipeline.md`
- **VirGL Test Pipeline** — **DEPLOYED**. Session 51. Harness updated with fix pipeline changes.
- **Mali GPU Acceleration** — **FIXES INSUFFICIENT**. EXTENSION_OVERRIDE + version fixes didn't resolve rendering.
- **Auth Session Refresh Fix** — **COMPLETE**. Session 45-46.
- **Lifecycle + GPU Acceleration** — **COMPLETE**. All 3 phases.
- **GeckoView Auth Integration** — **COMPLETE**.

## Reference
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

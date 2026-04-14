# Session State

**Last Updated**: 2026-03-16 | **Session**: 61

## Current Phase
- **Phase**: Direct Android Surface Spike — Presentation Pipeline 120 FPS Optimization
- **Status**: Full 4-phase optimization plan APPROVED. Spec written, API-audited (all endpoints verified current via Context7 + web research), blast radius analyzed, and implementation plan saved. Key insight: VirGL vtest synchronous readback is the structural bottleneck (~10-25ms/frame), Xlorie legacy drawing fallback is active on Mali (wrong format: `R8G8B8X8_UNORM` maps to `GL_RGB8`, should be `R8G8B8A8_UNORM`), and `waitForNextFrame` caps at ~60 FPS via 2-vsync cadence. Ready to begin Phase 1 implementation.

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 61: Deep analysis of the presentation pipeline bottleneck, then full brainstorming spec + API audit + implementation plan.**

Key findings this session:
1. **VirGL vtest synchronous readback identified as THE main bottleneck** — every frame does `glReadPixels` (2-5ms) + socket busy-wait + CPU memcpy + ShmPutImage + `glTexSubImage2D` re-upload. Total: 10-25ms/frame fully serialized.
2. **Xlorie legacy drawing is active because of wrong RGBA format** — code retries with `R8G8B8X8_UNORM` (value 2, `GL_RGB8`) which Mali rejects for EGLImage. Should use `R8G8B8A8_UNORM` (value 1, `GL_RGBA8`).
3. **`waitForNextFrame` creates 2-vsync cap** — set after `eglSwapBuffers`, not cleared until next choreographer callback. At 120Hz = ~60 FPS max theoretical.
4. **All APIs verified current** — `AHardwareBuffer_sendHandleToUnixSocket`, `eglCreateSyncKHR` (native fence), `eglDupNativeFenceFDANDROID`, `eglWaitSyncKHR`, `eglPresentationTimeANDROID`, `eglGetNativeClientBufferANDROID`, `glEGLImageTargetTexture2DOES` — all confirmed current for API 34 on Immortalis-G720.
5. **`EGL_ANDROID_native_fence_sync` confirmed on Mali** — both native driver and ANGLE backend support it.
6. **AFBC preservation confirmed** — omitting CPU flags on AHB allocation preserves ARM Frame Buffer Compression.
7. **VirGL THREAD_SYNC already enabled** — Termux virglrenderer build hardcodes it, so this is NOT an untapped optimization.

### What Needs to Happen Next

1. **P0: Begin Phase 1 — Fix legacy drawing fallback** — instrument `rendererTestCapabilities()`, change format to `R8G8B8A8_UNORM`, verify EGLImage path works on Mali.
2. **P0: Measure Phase 1 result** — expect ~60-65 FPS (from ~50 baseline).
3. **P1: Then Phase 2 — Remove `waitForNextFrame`** — start with Option A (post-swap fence only), measure, then Option B if safe.

## Blockers

**1. VirGL vtest synchronous readback is the structural FPS ceiling** — Every frame forces GPU→CPU→socket→CPU→GPU round-trip. This is architectural, not configuration. Phases 1-2 optimize around it; Phase 3 (AHB zero-copy) is the only path that eliminates it.

**2. Xlorie legacy drawing active on Mali due to wrong format** — `R8G8B8X8_UNORM` retry fails because it maps to `GL_RGB8`. Fix is `R8G8B8A8_UNORM`. Phase 1.

**3. `waitForNextFrame` 2-vsync cap** — Hard ceiling at ~60 FPS even if damage arrives faster. Phase 2.

## Recent Sessions

### Session 61 (2026-03-16)
**Work**: Deep analysis of presentation pipeline via 11 parallel research agents. Identified VirGL vtest synchronous readback as main bottleneck, Xlorie legacy drawing active due to wrong RGBA format, and `waitForNextFrame` 2-vsync cap. Wrote comprehensive 4-phase spec (`.claude/specs/2026-03-16-presentation-pipeline-120fps-spec.md`), verified all 8 APIs via Context7 + web research, found key correction (`R8G8B8X8_UNORM` → `R8G8B8A8_UNORM`), analyzed blast radius (11 files, 2 new), wrote 24-step implementation plan.
**Decisions**: 4-phase approach approved: (1) Fix RGBA format, (2) Remove frame pacing cap, (3) Hybrid lifecycle parity, (4) AHardwareBuffer zero-copy bypass. Skip patched-jar experiments for now — native pipeline optimization has higher expected ROI.
**Next**: Begin Phase 1 implementation (instrument + fix `rendererTestCapabilities()`).

### Session 60 (2026-03-15)
**Work**: Added a working `direct-jvm` real RuneLite launch mode, wired it through the real evidence harness, fixed launch-state false positives caused by appended Termux logs, fixed CRLF corruption in seeded direct classpaths, and validated a fresh launcher-vs-direct comparison. Added a direct-classpath override directory so patched jars can be injected without mutating the launcher-managed repository, then pulled and disassembled the live RuneLite jars to confirm the next patch target in `GpuPlugin.prepareInterfaceTexture(...)` / `drawUi(...)`.
**Decisions**: The RuneLite launcher bootstrap is not the main steady-state limiter. Keep `direct-jvm` as the patch/testing vehicle, and move the next work to targeted client-side jar experiments rather than more launcher/X11 plumbing.
**Next**: Patch and override the relevant RuneLite GPU client path, starting with the interface upload / synchronization path in `client-1.12.20.jar`.

### Session 59 (2026-03-15)
**Work**: Added native timing and cadence diagnostics in the Lorie renderer path, forced RuneLite GPU settings (`unlockFps=true`, `fpsTarget=120`, `vsyncMode=OFF`), captured default-res, half-res, and `--scale 1` internal-hybrid evidence runs, and wired the combined probe harness so it can drive `internal-hybrid` synthetic controls.
**Decisions**: The Android-present step is no longer the main suspect; the remaining ceiling is upstream in Linux/AWT/X11 frame production.
**Next**: Rerun the `internal-hybrid` synthetic control sequentially, then continue upstream client-path diagnostics.

### Session 58 (2026-03-15)
**Work**: Debugged the in-app `CmdEntryPoint` route until `internal-hybrid` successfully booted real RuneLite. Added startup-log preservation in `launch-runelite.sh`, proved donor `libXlorie.so` must be extracted from installed APK, fixed extraction target to Termux runtime filesystem.
**Decisions**: The in-app bootstrap route is now a real measurement path, not just a spike.
**Next**: Compare `internal-hybrid` against the external hybrid path then pivot toward deeper presentation-path changes.

### Session 57 (2026-03-15)
**Work**: Started the first in-app Java-side X server experiment. Added in-app `CmdEntryPoint.java`, introduced `internal-hybrid` presentation variant, built and installed app. First run failed before X11 socket creation.
**Decisions**: Stay on the app-owned server route. Debug boot path directly.
**Next**: Fix CmdEntryPoint boot failure.

## Active Plans

- **Presentation Pipeline 120 FPS Optimization** — **APPROVED**. Session 61. 4 phases, 24 steps. Spec + plan + blast radius complete. Ready for Phase 1.
- **Direct Android Surface Spike** — **SUPERSEDED by above plan**. Sessions 54-60 established the hybrid path, evidence harness, and direct-jvm vehicle. The new 120 FPS plan subsumes this.
- **VirGL Fix Pipeline** — **IMPLEMENTED, SUPERSEDED**. Session 53. Historical.
- **Auth Session Refresh Fix** — **COMPLETE**. Session 45-46.

## Reference
- **120 FPS spec**: `.claude/specs/2026-03-16-presentation-pipeline-120fps-spec.md`
- **120 FPS plan**: `.claude/plans/2026-03-16-presentation-pipeline-120fps.md`
- **120 FPS blast radius**: `.claude/dependency_graphs/2026-03-16-presentation-pipeline-120fps/blast-radius.md`
- **Hybrid iteration log**: `runelite-tablet/docs/hybrid-x11-iteration-log.md`
- **Clean synthetic probe harness**: `scripts/hybrid-x11-clean-probe.ps1`
- **Real RuneLite evidence harness**: `scripts/hybrid-x11-runelite-evidence.ps1`
- **VirGL capability dump**: `.claude/research/virgl-capabilities-dump.md`
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Vendored Xlorie**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/`

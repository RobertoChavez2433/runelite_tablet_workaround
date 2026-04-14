# Session State

**Last Updated**: 2026-04-14 | **Session**: 63

## Current Phase
- **Phase**: Clean Architecture Refactor + Testing Pipeline (prerequisite for 120 FPS iteration)
- **Status**: Comprehensive spec written (`.claude/specs/2026-04-14-clean-architecture-refactor-spec.md`). 8 phases, ~115 checklist items. Reviewed language choice (Kotlin stays — Android system integration requires it). Hard constraints established: 200 LoC max per class/method, 50 LoC max per initializer/provider/factory. Real-component testing philosophy (no mocking frameworks, only 5 Android-boundary fakes). Ready to begin Phase 1.

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 63: Architecture review, clean architecture spec, testing pipeline design.**

This session pivoted from direct 120fps implementation to establishing the architecture + testing foundation first. Key decisions:

1. **Kotlin stays** — evaluated switching languages; concluded the problem is hard in any language and Kotlin is the best tool for deep Android system integration (AIDL, Binder IPC, Services, BroadcastReceivers). No cross-platform framework supports this.
2. **Clean architecture spec written** — domain/data/presentation layer split with hard boundaries. Domain layer = pure Kotlin, zero Android imports.
3. **200 LoC hard limit on classes and methods** — 5 god classes (SetupViewModel 1,147, SetupOrchestrator 790, GeckoAuthActivity 512, HybridX11HostActivity 351, JagexOAuth2Manager 506) must be decomposed.
4. **50 LoC hard limit on bootstraps** — all initializers, providers, factories, `onCreate()` methods, DI modules must be ≤50 lines. AppContainer split into 7 focused modules.
5. **Real components first, fakes only at Android boundary** — no Mockito/MockK. Only 5 fakes (CommandRunner, CredentialStore, PackageChecker, SetupStateStore, Logger). Real OkHttp + MockWebServer for network tests. Real extracted classes tested directly.
6. **Zero tests exist today** — 45 Kotlin files, 0 test files, 0 CI/CD.

### What Needs to Happen Next

1. **P0: Execute Phase 1 — Foundation** — create test dirs, add deps, extract 4 interfaces (CommandRunner, CredentialStore, Logger, PackageChecker), write first test (PkceHelperTest), verify `./gradlew test` passes.
2. **P0: Execute Phase 2 — DI Container + Bootstrap Enforcement** — create 7 DI modules (≤50 lines each), slim bootstraps, create TestAppContainer.
3. **P1: Execute Phase 3 — God Class Decomposition** — split 5 god classes into ≤200 LoC each.
4. **P1: 120 FPS work is BLOCKED on Phase 4+ completion** — need fast iteration loop before changing rendering pipeline.

## Blockers

**1. VirGL vtest synchronous readback is the structural FPS ceiling** — Every frame forces GPU→CPU→socket→CPU→GPU round-trip. This is architectural, not configuration. Phases 1-2 optimize around it; Phase 3 (AHB zero-copy) is the only path that eliminates it.

**2. Xlorie legacy drawing active on Mali due to wrong format** — `R8G8B8X8_UNORM` retry fails because it maps to `GL_RGB8`. Fix is `R8G8B8A8_UNORM`. Phase 1.

**3. `waitForNextFrame` 2-vsync cap** — Hard ceiling at ~60 FPS even if damage arrives faster. Phase 2.

## Recent Sessions

### Session 63 (2026-04-14)
**Work**: Reviewed language choice (Kotlin vs alternatives). Ran 4 parallel research agents: current coupling analysis, rendering pipeline audit, testability gaps survey, Kotlin clean architecture best practices. Wrote comprehensive refactor spec with 8 phases, ~115 checklist items, 200 LoC class limit, 50 LoC bootstrap limit, real-component testing philosophy.
**Decisions**: Kotlin stays. 200 LoC hard limit on classes/methods. 50 LoC hard limit on bootstraps. No mocking frameworks — only 5 Android-boundary fakes. Domain layer must have zero Android imports. AppContainer split into 7 modules.
**Next**: Begin Phase 1 (test foundation + interface extraction).

### Session 61 (2026-03-16)
**Work**: Deep analysis of presentation pipeline. Identified VirGL vtest synchronous readback as main bottleneck, Xlorie legacy drawing active due to wrong RGBA format, `waitForNextFrame` 2-vsync cap. Wrote 4-phase spec, verified all 8 APIs, wrote 24-step implementation plan.
**Decisions**: 4-phase approach: (1) Fix RGBA format, (2) Remove frame pacing cap, (3) Hybrid lifecycle parity, (4) AHardwareBuffer zero-copy bypass.
**Next**: Begin Phase 1 implementation (now blocked on architecture refactor).

### Session 60 (2026-03-15)
**Work**: Added a working `direct-jvm` real RuneLite launch mode, wired it through the real evidence harness, fixed launch-state false positives caused by appended Termux logs, fixed CRLF corruption in seeded direct classpaths, and validated a fresh launcher-vs-direct comparison. Added a direct-classpath override directory so patched jars can be injected without mutating the launcher-managed repository, then pulled and disassembled the live RuneLite jars to confirm the next patch target in `GpuPlugin.prepareInterfaceTexture(...)` / `drawUi(...)`.
**Decisions**: The RuneLite launcher bootstrap is not the main steady-state limiter. Keep `direct-jvm` as the patch/testing vehicle, and move the next work to targeted client-side jar experiments rather than more launcher/X11 plumbing.
**Next**: Patch and override the relevant RuneLite GPU client path, starting with the interface upload / synchronization path in `client-1.12.20.jar`.

### Session 59 (2026-03-15)
**Work**: Added native timing and cadence diagnostics in the Lorie renderer path, forced RuneLite GPU settings (`unlockFps=true`, `fpsTarget=120`, `vsyncMode=OFF`), captured default-res, half-res, and `--scale 1` internal-hybrid evidence runs, and wired the combined probe harness so it can drive `internal-hybrid` synthetic controls.
**Decisions**: The Android-present step is no longer the main suspect; the remaining ceiling is upstream in Linux/AWT/X11 frame production.
**Next**: Rerun the `internal-hybrid` synthetic control sequentially, then continue upstream client-path diagnostics.

## Active Plans

- **Clean Architecture Refactor + Testing Pipeline** — **SPEC WRITTEN**. Session 63. 8 phases, ~115 checklist items. Spec: `.claude/specs/2026-04-14-clean-architecture-refactor-spec.md`. Ready for Phase 1.
- **Presentation Pipeline 120 FPS Optimization** — **APPROVED, BLOCKED on refactor**. Session 61. 4 phases, 24 steps. Spec + plan + blast radius complete. Needs testing pipeline from refactor before implementation.
- **Direct Android Surface Spike** — **SUPERSEDED**. Sessions 54-60.
- **Auth Session Refresh Fix** — **COMPLETE**. Session 45-46.

## Reference
- **Clean architecture refactor spec**: `.claude/specs/2026-04-14-clean-architecture-refactor-spec.md`
- **120 FPS spec**: `.claude/specs/2026-03-16-presentation-pipeline-120fps-spec.md`
- **120 FPS plan**: `.claude/plans/2026-03-16-presentation-pipeline-120fps.md`
- **120 FPS blast radius**: `.claude/dependency_graphs/2026-03-16-presentation-pipeline-120fps/blast-radius.md`
- **Hybrid iteration log**: `runelite-tablet/docs/hybrid-x11-iteration-log.md`
- **Clean synthetic probe harness**: `scripts/hybrid-x11-clean-probe.ps1`
- **Real RuneLite evidence harness**: `scripts/hybrid-x11-runelite-evidence.ps1`
- **VirGL capability dump**: `.claude/research/virgl-capabilities-dump.md`
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Vendored Xlorie**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/`

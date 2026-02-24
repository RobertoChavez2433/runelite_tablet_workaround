# Session State

**Last Updated**: 2026-02-24 | **Session**: 39

## Current Phase
- **Phase**: MVP Development — On-Device Testing & GPU Debugging
- **Status**: First full on-device test session. Fixed 3 bugs (shell syntax, env file deletion, stateStore cache). GPU packages installed but VirGL not working — Ubuntu ARM64 Mesa missing virpipe driver. RuneLite runs on llvmpipe (software) at 2960x1848.

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 39: On-device testing found 3 bugs, all fixed. GPU acceleration BLOCKED — Ubuntu's `libgl1-mesa-dri` on ARM64 doesn't include `virtio_gpu_dri.so` (virpipe Gallium driver). VirGL server runs, socket exists, but proot Mesa can't use it. RuneLite works but is choppy on software rendering.**

Key accomplishments:
1. **Shell syntax fix** — 16 unescaped `"` inside `bash -c "..."` block in launch-runelite.sh caused syntax error on `(` characters. All escaped to `\"`.
2. **Env file deletion fix** — `cleanup_previous()` deleted `.rlt-launch-env.sh` before the script read it. Removed premature deletion.
3. **stateStore cache fix** — `reconcileWithMarkers()` ABSENT branch downgraded step UI status but didn't clear `stateStore.isCompleted()` flag, so `executeGpuStep()` always skipped. Added `stateStore.clearCompleted(key)`.
4. **GPU packages installed** — `virglrenderer-android` + `angle-android` now install correctly during GPU setup step.
5. **VirGL starts but virpipe fails** — `virgl_test_server_android --angle-gl` runs (PID alive, socket at `$PREFIX/tmp/.virgl_test`), but `GALLIUM_DRIVER=virpipe glxinfo` returns empty inside proot. Mesa falls back to llvmpipe.

### What Needs to Happen Next

1. **BLOCKER: Install virpipe-capable Mesa inside proot** — Ubuntu's Mesa doesn't include `virtio_gpu_dri.so` on ARM64. Options: TUR Mesa package, custom Mesa build, or third-party PPA. This is the #1 priority.
2. **Verify VirGL GPU acceleration end-to-end** — Once virpipe driver available, confirm glxinfo shows VirGL renderer and RuneLite GPU plugin works.
3. **Consider production P1s** — Cert pinning, APK signature verification, permission "Don't ask again" UX.

## Blockers

**1. Ubuntu ARM64 Mesa missing virpipe driver** — `libgl1-mesa-dri` on ARM64 does NOT include `virtio_gpu_dri.so`. VirGL server runs but Mesa inside proot can't connect. Need a Mesa build with virpipe enabled. Research needed on TUR packages, custom builds, or PPAs.

## Recent Sessions

### Session 39 (2026-02-24)
**Work**: First on-device test session. Fixed 3 bugs: shell syntax (16 unescaped `"` in bash -c block), env file premature deletion, stateStore cache not cleared on ABSENT reconciliation. GPU packages install correctly but VirGL doesn't work — Ubuntu ARM64 Mesa missing virpipe driver.
**Decisions**: All `"` inside `bash -c "..."` must be escaped `\"`. stateStore must be cleared when marker reconciliation downgrades a step. Env file deletion moved out of cleanup_previous().
**Next**: Install virpipe-capable Mesa in proot (TUR/custom build), verify GPU acceleration, production P1s.

### Session 38 (2026-02-24)
**Work**: Implemented Mali GPU acceleration plan via `/implement`. 5 phases (GPU detection, Mali setup, launch script tiered fallback, Kotlin changes, shutdown cleanup). 7 files (2 new + 5 modified). Code review found 7 P1s, all fixed and verified. 2 builds passed.
**Decisions**: VirGL server tied to session lifecycle, GL version override AFTER GL check (not before), GPU setup non-blocking, polling loops (2s max) for VirGL readiness, 512MB disk space pre-check, grep -Eo (POSIX) not grep -oP (PCRE).
**Next**: On-device test (especially Mali VirGL spike), commit changes.

### Session 37 (2026-02-24)
**Work**: 6-wave review-fix-verify loop with 12 review agents. 21 fixes across 17 files. Standard reviews (code/perf/security), verification, final check, then 3 production-scrutiny waves (edge cases, stress/resilience, adversarial security). 4 logical commits.
**Decisions**: Double-quote shell escaping (not single-quote), IMMUTABLE_FLAGS for notification PendingIntents, sentinel file for health monitoring (not PID+pgrep), corrupted EncryptedSharedPreferences auto-recovery.
**Next**: On-device test of full app. Consider production P1s (cert pinning, APK sig verify).

### Session 36 (2026-02-24)
**Work**: Implemented lifecycle + GPU plan via `/implement` (3 phases, 6 quality gates, 1 orchestrator cycle). 5 new files, 9 modified. GeckoView auth also committed. 4 logical commits.
**Decisions**: Companion object MutableStateFlow for service-to-UI comm, startForeground in handleCheckSession for restart safety, GPU step non-blocking, POST_NOTIFICATIONS soft-prompted.
**Next**: On-device test lifecycle, GPU, and session UI.

### Session 35 (2026-02-23)
**Work**: 3 parallel research agents (perf logs, lifecycle, GPU). Analyzed GC/CPU/memory/resolution from device logs. Identified GPU-on-llvmpipe as #1 bottleneck. User applied quick wins. Brainstormed combined lifecycle + GPU design (6 decisions, 7 sections). Design doc committed.
**Decisions**: Keep running on swipe, notification with Switch/Stop actions, automated GPU setup step, auto-fallback to software, auto-detect running session, 15s health poll, Foreground Service + shell scripts approach.
**Next**: Implement lifecycle (Phase 1), then GPU (Phase 2), then polish (Phase 3).

## Active Plans

- **Mali GPU Acceleration** — **BLOCKED**. VirGL server works, but proot Mesa missing virpipe driver (`virtio_gpu_dri.so`). Need virpipe-capable Mesa build inside proot.
- **Lifecycle + GPU Acceleration** — **IMPLEMENTED + HARDENED**. All 3 phases complete, 6 quality gates + 6-wave review-fix-verify. Session 36-37. On-device tested Session 39 (lifecycle works, GPU blocked).
- **GeckoView Auth Integration** — **COMPLETE + HARDENED**. Implemented Session 34. Hardened Session 37. Credentials deploy correctly (Session 39 fix).
- **Phase 1 UX Revert + Extra Keys** — **COMPLETE**. Verified on device.
- **Permissions Automation** — **COMPLETE**. All 3 phases work, auto-advance verified.
- **Security Hardening** — **COMPLETE**. Session 22 initial + Session 37 production-level (21 fixes total).
- **Slice 4+5 Implementation** — **DESIGNED**. Plan committed. Auth blocker RESOLVED.
- **Slice 2+3 Implementation** — **COMPLETE**. Committed + security hardened.

## Reference
- **Mali GPU design**: `.claude/plans/2026-02-24-mali-gpu-acceleration-design.md`
- **Lifecycle + GPU design**: `.claude/plans/2026-02-23-lifecycle-gpu-design.md`
- **GeckoView auth design**: `.claude/plans/2026-02-23-geckoview-auth-integration-design.md`
- **OAuth 2-step flow research**: `.claude/research/jagex-oauth2-two-step-flow.md`
- **Slice 4+5 plan**: `.claude/plans/completed/2026-02-23-slice4-5-implementation-plan.md`
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Research**: `.claude/research/` (7 files + README)
- **Perf logs on device**: `~/runelite/gc.log`, `~/runelite/perf-monitor.log`, `~/runelite-launch.log`

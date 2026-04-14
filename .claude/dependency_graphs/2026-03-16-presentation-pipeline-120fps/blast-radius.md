# Blast Radius Analysis: Presentation Pipeline 120 FPS

**Date**: 2026-03-16
**Spec**: specs/2026-03-16-presentation-pipeline-120fps-spec.md

## Direct Changes (11 files)

| File | Phase | Risk | Lines Changed (est.) |
|------|-------|------|---------------------|
| `third_party/.../lorie/renderer.c` | 1,2,3 | HIGH | ~150 |
| `third_party/.../lorie/buffer.c` | 1,3 | HIGH | ~60 |
| `third_party/.../lorie/buffer.h` | 3 | MEDIUM | ~20 |
| `third_party/.../lorie/InitOutput.c` | 2 | MEDIUM | ~10 |
| `third_party/.../lorie/activity.c` | 3 | MEDIUM | ~40 |
| `third_party/.../lorie/lorie.h` | 2 | LOW | ~5 |
| NEW: `third_party/.../lorie/ahb_bridge.c` | 3 | HIGH | ~200 |
| NEW: `third_party/.../lorie/ahb_bridge.h` | 3 | MEDIUM | ~40 |
| `third_party/.../cpp/CMakeLists.txt` | 3 | LOW | ~5 |
| `.../hybrid/HybridX11HostActivity.kt` | 4 | LOW | ~30 |
| `.../termux/x11/CmdEntryPoint.java` | 3 | HIGH | ~80 |

## Dependent Files (touched indirectly)

| File | Why | Risk |
|------|-----|------|
| `.../termux/x11/LorieView.kt` | Phase 4: expose `reloadPreferences()` | LOW |
| `.../termux/x11/MainActivity.kt` | Phase 4: JNI shim for clientConnectedStateChanged | LOW |
| `runelite-tablet/app/build.gradle.kts` | Phase 3: if CMakeLists.txt adds new source files | LOW |

## Test/Evidence Files (updated for validation)

| File | Why |
|------|-----|
| `scripts/hybrid-x11-runelite-evidence.ps1` | Add Phase 1-3 diagnostic tags to logcat filter |
| `scripts/hybrid-x11-clean-probe.ps1` | Unchanged (existing probe shapes still valid) |
| `.../hybrid/HybridX11TestReceiver.kt` | May need new probe modes for AHB bypass validation |

## Cleanup Files

| File | Why |
|------|-----|
| `.claude/autoload/_state.md` | Update session state after each phase |
| `.claude/specs/2026-03-16-presentation-pipeline-120fps-spec.md` | Mark phases complete |
| `runelite-tablet/docs/hybrid-x11-iteration-log.md` | Append checkpoints |

## Risk Classification

### HIGH RISK (3 files)
- `renderer.c` — Core rendering loop. Phase 1 changes capability test; Phase 2 removes frame pacing; Phase 3 adds AHB sampling. Any regression breaks display entirely.
- `ahb_bridge.c` (NEW) — New IPC protocol. Bugs cause black screen or crash.
- `CmdEntryPoint.java` — VirGL server integration. FBO redirect failure = no rendering.

### MEDIUM RISK (4 files)
- `buffer.c` / `buffer.h` — Buffer format changes affect all buffer types.
- `activity.c` — Control channel setup; must not break existing connection path.
- `InitOutput.c` — Frame pacing removal; must not break X server event loop.

### LOW RISK (6 files)
- `lorie.h` — Only removing `waitForNextFrame` field usage.
- `CMakeLists.txt` — Adding one new source file.
- `HybridX11HostActivity.kt` — Adding lifecycle calls.
- `LorieView.kt` — Exposing existing method.
- `MainActivity.kt` — Unchanged or minor.
- `build.gradle.kts` — Unchanged or minor.

## Constraint Check

| Constraint | Status |
|-----------|--------|
| No rooting | OK — all changes are in app-owned code |
| No RuneLite modification | OK — no changes to RuneLite jar |
| Must stay on proot | OK — VirGL path preserved |
| Mali/Immortalis only | OK — RGBA format verified for Mali |
| Preserve input | OK — input controller untouched |
| Preserve auth | OK — auth flow untouched |
| Vendored code buildable | OK — CMakeLists.txt updated for new files |

## Phase Independence

Each phase can be deployed and measured independently:
- **Phase 1** modifies only `renderer.c` + `buffer.c` (capability test + format)
- **Phase 2** modifies only `renderer.c` + `InitOutput.c` + `lorie.h` (frame pacing)
- **Phase 4** modifies only `HybridX11HostActivity.kt` + `LorieView.kt` (lifecycle)
- **Phase 3** depends on Phase 1 (RGBA validation) but not on Phase 2 or 4

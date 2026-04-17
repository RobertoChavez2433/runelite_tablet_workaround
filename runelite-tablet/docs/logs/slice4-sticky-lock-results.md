# Slice 4: Sticky AHB Lock — A/B Results

**Date**: 2026-04-16 | **Session**: 74 | **Device**: R52X90378YB
**Location**: Varrock East Bank (user canonical test)
**Parent plan**: `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`
**Parent spec**: `.claude/specs/2026-04-14-120fps-rendering-pipeline-spec.md`

## What was tested

Env-flag (`RLT_STICKY_AHB_LOCK`) gated sentinel `$HOME/.rlt-sticky-ahb` toggles
the defensive unlock+relock dance in `lorieRedraw` (InitOutput.c:~467). When
on, the dance is replaced with a sticky hold for the AHB-backed root pixmap.

Counters added in `lorieUploadToScreen`, `loriePrepareAccess`, `lorieFinishAccess`,
and the flush path, logged every 5s under the `AhbLockTrace:` tag.

## Raw numbers (per 5s window, steady-state at Varrock East Bank)

| Counter | Baseline (sticky=0) | Sticky (sticky=1) |
|---|---|---|
| `lock_calls` | 225–258 | **0** |
| `unlock_calls` | 225–258 | **0** |
| `flush_skipped` | 0 | 219–254 |
| `sticky_hits` | 4342–5171 | 5040–5796 |
| `damage-triggered redraws` | 42.0–49.6 FPS | 46.6–50.8 FPS |
| `X frames / 5s` | 210–248 (42–50 FPS) | 233–255 (47–51 FPS) |
| `SurfaceView queueBuffer` | 43.3–53.9 FPS | 37.4–56.6 FPS |
| **FpsPlugin overlay (in-game)** | ~13 FPS | **13 FPS** |

## Verdict

**Sticky lock works exactly as designed and has zero measurable FPS impact.**

The patch zeroed all `LorieBuffer_lock/unlock` traffic in the damage-flush
path (`flush_skipped` ramps to ~234/5s = ~47 Hz, matching the redraw-flush
rate). Per-strip `UploadToScreen` was already sticky-by-default because
`lorieCreatePixmap` leaves the pixmap locked from birth; `sticky_hits`
(~1000/s) represents the 84-strip-per-frame reuse of that existing lock.

At 96 AHB-ops/sec saved (sticky_hits increased by ~0 Hz, lock_calls dropped
by ~96 Hz), the expected CPU win is well under 1%. The prior state memo's
"expected 2–3× FPS" estimate assumed the compositor's AHB lock was the
frame-time bottleneck — it isn't.

## What is the bottleneck (per this capture)

Scene FPS reconstruction from `sticky_hits` counter:

```
sticky_hits / 5s / 84 strips-per-frame = scene frames/sec
4800 / 5 / 84 ≈ 11.4 FPS  (matches FpsPlugin ~13)
```

The compositor is idle most of every polling cycle:

- `choreographer callbacks` = 120 FPS (display vsync, full headroom)
- `redraw wakeups` = 120 FPS (renderer wakes every vsync)
- `damage-triggered redraws` = 47 FPS (only 47/120 polls have damage content)

i.e., the X server wants to present at 120 Hz; the client is only producing
damage 47 times/sec (coalesced), which reduces to ~13 full scene frames/sec.

## Phase-1 CPU affinity is broken on this device

From the launch log during the sticky run:

```
AFFINITY: pid=20227 name=virgl_test_serv cpu=0 type=little freq=2000000
AFFINITY_MIGRATION: pid=20227 from=0(little) to=0(little) re-pinned=true
```

The launch script prints `AFFINITY: VirGL pinned to big/prime cores (mask=0xF0)`
but the monitor shows cpu=0 and the effective mask is `0xf` (little cluster
only). Android's EAS scheduler overrides the `taskset -p 0xF0 $VIRGL_PID`
call issued by `launch-runelite.sh`. The re-pin watchdog (Phase 1 Task 1B)
fires every second with `re-pinned=true` but the next sample shows the
process still on CPU 0 — i.e. taskset is being silently rejected or
immediately reverted.

This is the exact scenario the 120 FPS spec flagged as Root Cause #1
(40–70% throughput loss) and recorded as a Phase 1 risk ("Android EAS
re-migrates pinned processes"). The mitigation (Task 1B periodic re-pin)
is not working.

## Gate decision vs spec

**Spec verification gate** (`.claude/specs/2026-04-14-120fps-rendering-pipeline-spec.md`):
```
damage-triggered redraws >= 120 FPS
estimated_fps            >= 120 FPS
content_util             = 100%
avg_inter_frame_ms       <= 8.33 ms
sustained                >= 60s
```

Current state after Slice 4: damage redraws = 47, in-game FPS = 13. **Gate not met.**

Slice 4 status: **landed, no regression, measured null**. Left enabled via
default-off sentinel (`$HOME/.rlt-sticky-ahb`) for opt-in A/B; safe to
leave off permanently or promote to default-on (no measured cost in the
on path either).

## Root-cause reframe

The plan's Slice 4 → Slice 5 path assumed the compositor was the bottleneck
and that reducing compositor overhead would lift FPS. The evidence says the
opposite: the compositor has 2.5× idle headroom (120 Hz capacity vs 47 Hz
demand). Presentation-side work (Slice 5 direct SurfaceView) may drop some
latency but cannot plausibly lift scene FPS by more than a few frames per
second because the scene producer is running at 11–13 Hz.

The 120 FPS target requires the client-side producer to emit scenes ~10×
faster. That points at Part 1 Phases 1, 3, 4 of the spec (CPU affinity,
VirGL tuning, ptrace reduction), not the presentation-pipeline slices.

## Recommended next steps (in order)

1. **Fix CPU affinity** so virgl_test_server and the RuneLite JVM actually
   run on CPU 4–7. Options: kernel-level `sched_setaffinity` via JNI
   (bypasses shell-level taskset), or launch virgl under a wrapper that
   re-pins from inside (not just externally).
2. **Enable seccomp-bpf** (`RLT_PROOT_SECCOMP=1`). Single env-var toggle.
   Expected drop of `kernel_ratio` from ~0.54 to ~0.20.
3. **Re-measure** scene FPS after (1)+(2). If >= 30 FPS, continue toward
   Slice 5 for presentation latency. If still stuck at 13, the remaining
   overhead is VirGL IPC serialization itself (Part 2 territory: Phase 5
   AHB zero-copy, Phase 6 custom GL command proxy).

## Files touched in Slice 4

- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c`
  — counters, flag, sticky-lock guard in `lorieRedraw`.
- `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
  — sentinel-based `RLT_STICKY_AHB_LOCK` export, companion FPS-probe sentinel.
- `scripts/deploy-native-to-device.sh` — helper so rebuilds ship both APKs
  together (the session-74 install mistake).

## Logs archived

- `runelite-tablet/docs/logs/slice4-baseline-60s.log` (~3.4 MB, sticky=0)
- `runelite-tablet/docs/logs/slice4-sticky-60s.log` (~3.7 MB, sticky=1)

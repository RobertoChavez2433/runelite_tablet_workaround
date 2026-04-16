# FPS Ceiling Research — Session 70 (2026-04-16)

## Problem Statement

Native FPS capped at 42-54 FPS despite 120 FPS choreographer on Samsung Tab S10 Ultra
(Mali-G720 Immortalis MC12, Dimensity 9300+, 12GB RAM, API 36).

RuneLite runs inside proot Ubuntu ARM64 with OpenJDK 11. Display via Termux:X11 (Xlorie).
GL translation via VirGL vtest server (`virgl_test_server_android`).

Prior state file listed three blockers:
1. VirGL vtest synchronous readback is the structural FPS ceiling (42-54 FPS)
2. Xlorie legacy drawing active on Mali due to wrong format
3. `waitForNextFrame` 2-vsync cap

---

## Frame Production Pipeline (verified)

```
RuneLite (Java, proot)
  → Mesa virpipe gallium driver
    → Unix socket (/tmp/.virgl_test)
      → virgl_test_server_android (Termux process)
        → Android GLES (Mali-G720)
          → X11 drawable (shared memory pixmap)

X server (CmdEntryPoint, app_process)
  → detects X11 damage
  → composites to root window pixmap (AHARDWAREBUFFER, zero-copy)
  → sets drawRequested=TRUE, signals renderer

Choreographer (CmdEntryPoint Looper thread, 120 Hz)
  → clears waitForNextFrame (dead flag — see below)
  → queues lorieRedraw work proc
  → wakes X server event loop

Renderer thread (activity-side gles-renderer)
  → wakes on pthread_cond_signal
  → binds AHARDWAREBUFFER texture (glEGLImageTargetTexture2DOES — zero-copy)
  → draws fullscreen quad via GLES2
  → eglClientWaitSyncKHR (fence, ~2.7ms avg)
  → eglSwapBuffers (~0.6ms avg)
  → Android SurfaceFlinger composites to display at 120 Hz
```

### Key file paths

| Component | Path | Hot lines |
|-----------|------|-----------|
| Renderer (draw, fence, perf) | `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c` | 738-809 (rendererRedrawLocked), 811-837 (rendererShouldWait), 204-260 (rendererLogPerfSample) |
| X server (choreographer, damage, present) | `third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c` | 438-489 (lorieRedraw), 491-510 (lorieFramecounter), 682-693 (lorieChoreographerFrameCallback), 840-913 (loriePresentFlip) |
| Buffer management (texture upload) | `third_party/termux-x11-upstream/app/src/main/cpp/lorie/buffer.c` | 431-438 (LorieBuffer_bindTexture) |
| Shared state struct | `third_party/termux-x11-upstream/app/src/main/cpp/lorie/lorie.h` | 195-197 (waitForNextFrame field) |
| Capability probe (format selection) | `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c` | 416-593 (rendererTestCapabilities) |
| VirGL launch + env vars | `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh` | 365-397 (X11 server launch), 509-597 (VirGL server launch) |
| Kotlin host activity | `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt` | 54-89 (Choreographer callback — measures only, does NOT drive native) |
| Attach controller | `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/X11AttachmentController.kt` | 38-53 (startAttachLoop), 79-140 (tryAttach) |
| Session health | `runelite-tablet/app/src/main/java/com/runelitetablet/session/SessionHealthMonitor.kt` | 41-49 (initialDelay), 131-164 (debounce) |
| triggerCallback | `runelite-tablet/app/src/main/java/com/termux/x11/LorieView.kt` | 102-118 |

---

## Hypotheses Tested

### H1: Present flip rejection forces slow damage-copy path

**Hypothesis**: `loriePresentFlip` (InitOutput.c:874) rejects VirGL-imported FD buffers
unless `TERMUX_X11_FORCE_FLIP=1`. Every frame goes through slow damage-copy path
instead of zero-copy flip. Setting the env var will activate flips and improve FPS.

**Changes made**:
- `launch-runelite.sh`: Added `export TERMUX_X11_FORCE_FLIP=1` before X11 server launch
- `InitOutput.c:910-913`: Added H1-MARKER log on flip acceptance
- `buffer.c:431-438`: Added H1-MARKER log when glTexSubImage2D (slow FD path) fires

**Evidence collected (device, 2026-04-16 11:35-11:52)**:
```
present flip attempts in 5.0 seconds = 0.0 FPS (accepted 0.0, rejected 0.0)
present after-flips in 5.0 seconds = 0.0 FPS
H1-MARKER (glTexSubImage2D): never fired
H1-MARKER (PresentFlip accepted): never fired
```

**Verdict: REJECTED**

VirGL/Mesa's virpipe driver does NOT use the X11 Present extension at all. Zero flip
attempts means the rejection gate is never reached. The env var has no effect.

The root window pixmap already uses AHARDWAREBUFFER (confirmed: `legacy_drawing=0`,
no glTexSubImage2D calls). The "slow damage-copy path" hypothesis was wrong — the
pipeline was already on the efficient path.

**Why this was wrong**: The original blocker stated "Xlorie legacy drawing active on Mali
due to wrong format." Device logs show `legacy_drawing=0 flip=0`, meaning the BGRA
AHARDWAREBUFFER path IS active. The renderer's `rendererTestCapabilities` correctly
probed BGRA support and selected the fast path. The "wrong format" blocker was stale.

### H2: VirGL single-threaded command processing is the ceiling

**Hypothesis**: Enabling `VIRGL_RENDERER_THREAD=1 VIRGL_RENDERER_ASYNC=1` will pipeline
GL command processing in the VirGL server, reducing per-frame socket round-trip cost.

**Changes made**:
- `launch-runelite.sh`: Removed `RLT_VIRGL_TUNE` feature flag gate; threading always on

**Evidence collected**:
```
Launch log confirms: "VirGL server tuning: VIRGL_RENDERER_THREAD=1 VIRGL_RENDERER_ASYNC=1"
damage-triggered redraws: 44.0-50.6 FPS (baseline was 42-54 FPS)
estimated_fps (renderer): 33.5-59.5 FPS (baseline was similar range)
```

**Verdict: REJECTED**

No measurable improvement. The threading env vars either don't affect vtest mode, or
RuneLite's GL workload is already serialized by synchronous operations (queries,
glGetError, etc.) that can't be pipelined.

### H3: VirGL vtest socket serialization is the structural ceiling

**Hypothesis**: The per-frame cost of VirGL command serialization over Unix socket is
the fundamental limit. Content production rate is 40-50 FPS regardless of compositor
optimizations.

**Evidence collected**:
```
XloriePerf (steady state, RuneLite lobby screen):
  avg_frame_ms     = 3.2-4.4 ms  (renderer GPU time per frame)
  avg_inter_frame_ms = 16.8-29.9 ms  (time BETWEEN frames = VirGL production time)
  content_util     = 100%  (renderer NEVER starves — always has a frame to present)
  avg_fence_ms     = 2.3-2.9 ms  (GPU pipeline stall, ~5% of frame budget)
  avg_swap_ms      = 0.5-0.8 ms  (buffer swap, negligible)
  avg_lock_ms      = 0.001-0.005 ms  (mutex, negligible)

LorieNative (5-second windows):
  choreographer callbacks = 120.0 FPS  (display is ready for content)
  redraw wakeups          = 120.0 FPS  (renderer is being woken)
  damage-triggered redraws = 44-51 FPS  (actual content production rate)
  present flip attempts    = 0.0 FPS  (Present extension not used)
```

**Verdict: CONFIRMED**

The renderer spends only 3-4ms drawing each frame but waits 17-30ms for the next frame
from VirGL. The bottleneck is entirely upstream of the compositor. The inter-frame gap
IS the VirGL processing time — every GL call from RuneLite pays a Unix socket round-trip.

---

## Renderer Overhead Breakdown (per frame, steady state)

| Stage | Avg ms | Max ms | Notes |
|-------|--------|--------|-------|
| Mutex lock | 0.003 | 0.268 | pthread_mutex_timedlock (33ms timeout), almost never contended |
| Texture bind | ~0 | ~0 | AHARDWAREBUFFER via glEGLImageTargetTexture2DOES, zero-copy |
| Draw (quad + cursor) | <0.5 | <1.0 | Two glFlush calls, GLES2 fullscreen quad |
| Fence wait | 2.7 | 5.95 | eglClientWaitSyncKHR(EGL_FOREVER), blocks renderer |
| Swap | 0.65 | 2.96 | eglSwapBuffers, eglSwapInterval=0 (non-blocking) |
| **Total frame** | **3.8** | **8.5** | |
| **Inter-frame gap** | **22.0** | **69.0** | VirGL content production time |

The fence wait (2.7ms) is the largest per-frame cost on the renderer side. It could be
deferred to the start of the next frame (wait for PREVIOUS frame's fence before locking
the mutex for the CURRENT frame). This would save ~2.7ms of pipeline stall but NOT
improve content production rate — the bottleneck is VirGL, not the renderer.

**Fence deferral implementation notes** (renderer.c:792-799):
- Current: create fence → glFlush → cursor → glFlush → wait fence → unlock mutex → swap
- Proposed: store fence as static → at NEXT frame start: wait prev fence → destroy → lock mutex → draw
- Risk: mutex unlock before fence completes means X server can modify root window while GPU reads
- Mitigation: double-buffer the root window pixmap (one for GPU read, one for X server write)
- Complexity: HIGH for ~2.7ms gain. Not worth pursuing until VirGL ceiling is addressed.

---

## Dead Code: waitForNextFrame

The `waitForNextFrame` field (lorie.h:197) was a 2-vsync cap removed in Phase 2B:

- **Declared**: `lorie.h:197` — `volatile uint8_t waitForNextFrame`
- **Never set to true**: The only assignment was removed (renderer.c:797, now a comment)
- **Cleared to false**: `InitOutput.c:691` — `pvfb->state->waitForNextFrame = false` (by choreographer)
- **Checked**: `renderer.c:827` — `if (state->waitForNextFrame) return true` (dead branch)

Field initializes to 0 from mmap zero-init. Never set to 1 anywhere. The check is dead
code that never blocks. Safe to remove but has zero performance impact.

---

## P1: Attach Loop (VERIFIED FIXED)

**Issue**: X11AttachmentController retried every 250ms even after connection (attempt=280+).

**Current state** (X11AttachmentController.kt):
- Guard at line 39-42: returns early if `xConnectionAttached && !pendingBridgeReconnect`
- Cancellation at line 90: `attachJob?.cancel()` when `connector.isConnected()` returns true
- Cancellation at line 134: `attachJob?.cancel()` after successful `attachXConnection()`

**Device evidence (2026-04-16)**:
```
attempt=1 → tryAttach → xConnection attached latency=1ms
(no further attempts logged)
```

**Verdict**: Fixed. Single attempt, immediate connection, no retry spam.

---

## P1: Session Health Monitor First-Poll (VERIFIED FIXED)

**Issue**: First health poll shows `session=no virgl=n/a` before sentinel exists.

**Current state** (SessionHealthMonitor.kt):
- `initialDelayMs` parameter (line 41-49): delays first poll
- STOPPED debounce: 3 consecutive readings required (line 145: `STOPPED_THRESHOLD = 3`)
- Running state emits immediately (no debounce)
- Test coverage at SessionHealthMonitorTest.kt lines 81, 182

**Device evidence**: Health monitor transitions `Starting → Running` after first successful
health check. No false STOPPED emissions observed.

---

## P2: triggerCallback Delay (CONFIRMED, LOW PRIORITY)

**Issue**: `triggerCallback()` in LorieView.kt posts to UI thread; 34-43ms delay observed.

**Device evidence (2026-04-16)**:
```
triggerCallback: post delayed 43ms (UI thread saturated?)
triggerCallback: post delayed 43ms (UI thread saturated?)
```

**Root cause**: During initial X11 attach, the UI thread processes a burst of binder
callbacks, surface changes, and state updates. The `post {}` runnable queues behind this
burst. Only occurs during initial connection (2 occurrences, startup only).

**Impact**: Delays initial surface configuration by ~43ms. No runtime impact.

**Possible mitigation**: Move `surfaceChanged` call to a dedicated handler thread. But
the JNI call requires the GL context thread, making this non-trivial. Not worth pursuing.

---

## Changes Made This Session

### launch-runelite.sh

1. **Added `TERMUX_X11_FORCE_FLIP=1`** (before X11 server launch, ~line 371)
   - Intended to enable Present flip zero-copy path for VirGL FD buffers
   - **No effect**: VirGL doesn't use Present extension. Harmless to keep.

2. **Enabled VirGL threading by default** (~line 549-553)
   - Removed `RLT_VIRGL_TUNE` feature flag gate
   - Always sets `VIRGL_RENDERER_THREAD=1 VIRGL_RENDERER_ASYNC=1`
   - **No measurable FPS improvement**. Harmless to keep.

### Native code (temporary hypothesis markers — SHOULD BE REMOVED)

3. **buffer.c:431-438**: H1-MARKER log in `LorieBuffer_bindTexture` for glTexSubImage2D path
   - Added `#include <android/log.h>` and counter-based logging
   - **Temporary — remove after this session**

4. **InitOutput.c:910-913**: H1-MARKER log in `loriePresentFlip` acceptance path
   - Counter-based log on flip acceptance
   - **Temporary — remove after this session**

---

## What Would Actually Improve FPS

The bottleneck is VirGL vtest socket serialization. Realistic options ranked by impact:

### 1. Mesa Venus protocol (HIGH impact, HIGH effort)
Venus is the successor to virpipe for VirGL. Uses Vulkan-based command stream with
batched submission instead of per-GL-call socket round-trips. Would need:
- Venus-capable virglrenderer build for Android
- Mesa built with Venus gallium driver instead of virpipe
- Compatible Vulkan driver on host (Mali has native Vulkan)

References: mesa/venus, virglrenderer venus branch

### 2. Profile virgl_test_server hot path (MEDIUM impact, MEDIUM effort)
Identify which GL commands consume most socket round-trips. RuneLite's GPU plugin may
issue many small draw calls or synchronous queries that serialize badly over vtest.
Could optimize by:
- Tracing vtest protocol commands (virglrenderer has debug logging)
- Identifying sync points that force round-trips (glGetError, queries)
- Patching RuneLite GPU plugin to batch draws (upstream contribution)

### 3. Investigate `--no-loop-or-fork` VirGL mode (LOW impact, LOW effort)
The `virgl_test_server_android` forks per client connection. The `--no-loop-or-fork`
mode avoids this overhead. Profile already exists in launch script (`no-loop-or-fork`
case in the server profile switch). Quick test.

### 4. Software rendering baseline comparison (LOW impact, LOW effort)
Run with llvmpipe (no VirGL) to see CPU-only FPS. If llvmpipe > 50 FPS, VirGL overhead
is the problem. If llvmpipe < 50 FPS, RuneLite itself is the ceiling. This isolates
whether the bottleneck is VirGL transport or RuneLite rendering complexity.

### 5. Accept 40-50 FPS as the VirGL ceiling (ZERO effort)
OSRS itself runs at 50 FPS cap on desktop. The 40-50 FPS through VirGL is very close
to native OSRS frame rate. This may be acceptable for the user.

---

## Device Test Commands (for future sessions)

```bash
# Launch app through main activity (triggers full setup + launch)
adb -s R52X90378YB shell am start -n com.runelitetablet/.MainActivity

# Tap "Launch RuneLite" button (y=1550 in landscape 2960x1848)
adb -s R52X90378YB shell input tap 1480 1550

# Watch native perf logs
adb -s R52X90378YB logcat -s gles-renderer:D LorieNative:I LoriePerf:I

# Watch app logs
adb -s R52X90378YB logcat -s RLT:V

# Check launch script output (env vars, VirGL, X11)
adb -s R52X90378YB shell "run-as com.termux cat /data/data/com.termux/files/home/runelite-launch.log" | tail -50

# Kill session + clean sentinel (before fresh test)
adb -s R52X90378YB shell am force-stop com.runelitetablet
adb -s R52X90378YB shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -c 'rm -f /data/data/com.termux/files/usr/tmp/.rlt-session-alive; pkill -f virgl_test_server; pkill -f CmdEntryPoint; pkill -f proot'" 2>/dev/null

# Screenshot
adb -s R52X90378YB exec-out screencap -p > screenshot.png
```

---

## Perf Data Snapshot (lobby screen, steady state, 2026-04-16 11:52)

```
choreographer callbacks = 120.0 FPS
redraw wakeups          = 120.0 FPS
damage-triggered redraws = 44-51 FPS
present flip attempts    = 0.0 FPS (accepted 0.0, rejected 0.0)
present after-flips      = 0.0 FPS

XloriePerf (1-second windows, 30 samples):
  estimated_fps range: 33.5 — 59.5
  avg_fence_ms range:  2.26 — 2.97
  avg_swap_ms range:   0.50 — 0.78
  avg_frame_ms range:  3.22 — 4.43
  avg_inter_frame_ms:  16.80 — 29.86
  content_util:        100% (all windows)
  starved:             0 (all windows)
```

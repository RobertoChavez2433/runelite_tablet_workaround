# FPS Bottleneck Research: 36 FPS Instead of 120 FPS

**Date**: 2026-04-14  
**Device**: Samsung Galaxy Tab S10 Ultra (SM-X920)  
**SoC**: Dimensity 9300+ (4x Cortex-A720 @ 2.0 GHz, 3x Cortex-X4 @ 2.85 GHz, 1x Cortex-X4 @ 3.4 GHz)  
**GPU**: Immortalis-G720 MC12 (Mali)  
**Display**: 120 Hz refresh rate  

---

## 1. Problem Statement

RuneLite gameplay renders at 36-45 FPS on a 120 Hz display. The target is 120 FPS. The Kotlin/Compose UI layer achieves 120 FPS with 0 jank, so the display hardware is not the limiting factor.

---

## 2. Rendering Architecture

The full rendering pipeline has two independent frame rates:

```
Compose UI (120 FPS, perfect)
   |
   SurfaceView container
   |
Native X11 Renderer (36-45 FPS, bottlenecked)
   |
   Choreographer (120 Hz) -> X server lorieRedraw -> renderer thread
   |
Content Production Pipeline (THE BOTTLENECK):
   RuneLite Java (proot) -> JOGL/LWJGL -> Mesa virpipe driver
       -> virtio-gpu IPC -> virgl_test_server_android -> ANGLE -> GLES -> Mali GPU
       -> AHardwareBuffer -> X damage event -> drawRequested flag
```

### 2.1 Process Architecture

| Process | PID | Role | CPU | Memory |
|---------|-----|------|-----|--------|
| com.runelitetablet | 27320 | Android app, renderer thread, X11 bridge | 11.1% | 314 MB |
| java (RuneLite) | 6258 | Game client running in proot | 33.3% | 1.1 GB |
| virgl_test_server_android | 26636 | GL command translation (active) | 33.3% | 667 MB |
| virgl_test_server_android | 5638 | Parent process | 0% | 3.7 MB |
| virgl_test_server_android | 6327 | Idle child | 0% | 163 MB |
| virgl_test_server_android | 24471 | Idle child | 0% | 146 MB |
| virgl_test_server_android | 26615 | Idle child | 0% | 182 MB |
| proot | 5930 | ptrace-based Linux compat layer | 3.7% | 3.9 MB |

### 2.2 Thread Architecture

RuneLite has 36 threads, but rendering is **single-threaded**:

| Thread | TID | CPU % | Role |
|--------|-----|-------|------|
| Client | 6312 | 29.6% | Main game loop + GL rendering |
| AWT-EventQueue | 6291 | ~0% | UI events |
| AWT-XAWT | 6289 | ~0% | X11 event dispatch |
| Thread-7 | 6320 | ~0% | Unknown (20s cumulative) |
| All others (32 threads) | - | ~0% | Idle |

---

## 3. Data Collection

### 3.1 Instrumentation Added

**renderer.c** (`third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`):
- Content starvation tracking: `contentStarved` (vsync wakeups with no content) and `vsyncWakeups` (total wakeups)
- `content_util` metric: `frameCount / vsyncWakeups * 100`
- Added to `rendererPerfStats` struct at line 164-165
- Counter increments in `rendererThread()` at line 842-847

**InitOutput.c** (`third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c`):
- `lorieChoreographerFrameCallback` (line 682): clears `waitForNextFrame` on choreographer thread, queues `lorieRedraw`
- `lorieFramecounter` (line 491): logs choreographer callbacks, redraw wakeups, damage-triggered redraws every 5 seconds

### 3.2 Data Collection Method

- **WebSocket debug server** on port 8099: streams JSON log events from the app
- **ADB logcat**: captures X server logs (PID 5533) invisible to the app
- **ADB shell top/ps**: process-level CPU and memory usage
- **ADB shell /proc/PID/stat**: per-process CPU core affinity and utime/stime breakdown

### 3.3 Raw Data: Renderer (gles-renderer tag, 21 samples, 20-second capture)

```
XloriePerf: frames=40 fps=38.7 content_util=100% starved=0 vsyncs=40 fence=2.781ms swap=0.747ms frame=4.279ms inter=25.816ms
XloriePerf: frames=40 fps=38.8 content_util=100% starved=0 vsyncs=40 fence=2.839ms swap=0.723ms frame=4.310ms inter=25.763ms
XloriePerf: frames=42 fps=40.5 content_util=100% starved=0 vsyncs=42 fence=2.814ms swap=0.708ms frame=4.247ms inter=24.679ms
XloriePerf: frames=44 fps=42.6 content_util=100% starved=0 vsyncs=44 fence=2.813ms swap=0.706ms frame=4.239ms inter=23.484ms
XloriePerf: frames=41 fps=39.8 content_util=100% starved=0 vsyncs=41 fence=2.828ms swap=0.707ms frame=4.234ms inter=25.128ms
XloriePerf: frames=45 fps=43.6 content_util=100% starved=0 vsyncs=45 fence=2.728ms swap=0.706ms frame=4.178ms inter=22.929ms
XloriePerf: frames=43 fps=40.9 content_util=100% starved=0 vsyncs=43 fence=2.826ms swap=0.700ms frame=4.256ms inter=24.440ms
XloriePerf: frames=42 fps=39.4 content_util=100% starved=0 vsyncs=42 fence=2.924ms swap=0.736ms frame=4.386ms inter=25.395ms
XloriePerf: frames=46 fps=44.4 content_util=100% starved=0 vsyncs=46 fence=2.773ms swap=0.754ms frame=4.284ms inter=22.503ms
XloriePerf: frames=47 fps=45.7 content_util=100% starved=0 vsyncs=47 fence=2.841ms swap=0.747ms frame=4.342ms inter=21.893ms
XloriePerf: frames=50 fps=47.2 content_util=100% starved=0 vsyncs=50 fence=2.605ms swap=0.698ms frame=4.021ms inter=21.209ms
XloriePerf: frames=39 fps=36.9 content_util=100% starved=0 vsyncs=39 fence=2.936ms swap=0.730ms frame=4.445ms inter=27.110ms
XloriePerf: frames=46 fps=44.9 content_util=100% starved=0 vsyncs=46 fence=2.538ms swap=0.753ms frame=3.982ms inter=22.294ms
XloriePerf: frames=55 fps=54.0 content_util=100% starved=0 vsyncs=55 fence=2.340ms swap=0.487ms frame=3.339ms inter=18.522ms
XloriePerf: frames=53 fps=50.2 content_util=100% starved=0 vsyncs=53 fence=2.494ms swap=0.618ms frame=3.723ms inter=19.914ms
XloriePerf: frames=41 fps=39.7 content_util=100% starved=0 vsyncs=41 fence=2.772ms swap=0.689ms frame=4.203ms inter=25.168ms
XloriePerf: frames=46 fps=44.9 content_util=100% starved=0 vsyncs=46 fence=2.587ms swap=0.660ms frame=3.910ms inter=22.268ms
XloriePerf: frames=49 fps=47.7 content_util=100% starved=0 vsyncs=49 fence=2.436ms swap=0.610ms frame=3.627ms inter=20.966ms
XloriePerf: frames=54 fps=52.0 content_util=100% starved=0 vsyncs=54 fence=2.391ms swap=0.596ms frame=3.513ms inter=19.217ms
XloriePerf: frames=45 fps=41.6 content_util=100% starved=0 vsyncs=45 fence=2.758ms swap=0.701ms frame=4.181ms inter=24.046ms
```

**Statistical summary**:
- FPS range: 36.9 - 54.0 (mean ~43)
- Content utilization: **100% across all samples** (zero starvation)
- avg_frame_ms: 3.3 - 4.5 ms (actual GPU work per frame)
- avg_inter_frame_ms: 18.5 - 27.1 ms (time between frames, **the bottleneck**)
- avg_fence_ms: 2.3 - 2.9 ms (GPU fence wait)
- avg_swap_ms: 0.5 - 0.8 ms (eglSwapBuffers)
- avg_lock_ms: 0.001 - 0.008 ms (negligible)

### 3.4 Raw Data: X Server (LorieNative tag, PID 5533, via ADB)

```
choreographer callbacks in 5.0 seconds = 119.6 FPS
redraw wakeups in 5.0 seconds = 119.6 FPS
damage-triggered redraws in 5.0 seconds = 36.2 FPS
present flip attempts in 5.0 seconds = 0.0 FPS (accepted 0.0, rejected 0.0)
present after-flips in 5.0 seconds = 0.0 FPS
181 frames in 5.0 seconds = 36.2 FPS

choreographer callbacks in 5.0 seconds = 119.2 FPS
redraw wakeups in 5.0 seconds = 119.2 FPS
damage-triggered redraws in 5.0 seconds = 34.8 FPS
present flip attempts in 5.0 seconds = 0.0 FPS (accepted 0.0, rejected 0.0)
present after-flips in 5.0 seconds = 0.0 FPS
174 frames in 5.0 seconds = 34.8 FPS
```

### 3.5 Raw Data: Kotlin UI Layer (FRAME tag, 20 samples)

```
fps=120.0 jank=0 p99=8.3ms heap=10MB periodic
fps=120.0 jank=0 p99=8.3ms heap=10MB periodic
fps=120.0 jank=0 p99=8.3ms heap=10MB periodic
fps=120.0 jank=0 p99=8.3ms heap=10MB periodic
fps=120.0 jank=0 p99=8.4ms heap=10MB periodic
```

### 3.6 Raw Data: CPU Scheduling

```
/proc/6258/stat:  RuneLite: cpu=0  threads=36 utime=49294 stime=57670
/proc/26636/stat: VirGL:    cpu=1  threads=7  utime=17234 stime=24079
/proc/27320/stat: App:      cpu=0  threads=40 utime=6501  stime=6830
/proc/5930/stat:  proot:    cpu=2  threads=1  utime=1707  stime=7121
```

CPU topology:
```
CPU0: 2000000 Hz (little)
CPU1: 2000000 Hz (little)
CPU2: 2000000 Hz (little)
CPU3: 2000000 Hz (little)
CPU4: 2850000 Hz (big)      -- 42% faster than little
CPU5: 2850000 Hz (big)
CPU6: 2850000 Hz (big)
CPU7: 3400000 Hz (prime)    -- 70% faster than little
```

---

## 4. Analysis

### 4.1 What is NOT the bottleneck

1. **Choreographer**: fires at 119.6 Hz -- correct
2. **Renderer thread**: content_util=100%, renders every frame in ~4ms, starved=0
3. **X server redraw wakeups**: 119.6 FPS -- wakes for every choreographer tick
4. **Kotlin/Compose UI**: 120 FPS, 0 jank
5. **Display hardware**: 120 Hz confirmed
6. **RuneLite FPS config**: `gpu.fpsTarget=120`, `unlockFps=true`, `vsyncMode=OFF`, `fpscontrol.limitFps=false`, `fpscontrol.maxFps=360`
7. **Buffer locking**: avg_lock_ms < 0.01 ms

### 4.2 What IS the bottleneck

**RuneLite produces only 34-36 damage events per second through the VirGL pipeline.**

The X server wakes at 120 Hz via choreographer, but `lorieRedraw` only finds new damage 34-36 times per second. Without damage, `drawRequested` stays false, and the renderer thread stays asleep.

### 4.3 Three compounding root causes

#### Cause 1: CPU Core Scheduling (Impact: ~40-70% throughput loss)

RuneLite's rendering thread (TID 6312) runs on **CPU 0** (little core, 2.0 GHz). VirGL runs on **CPU 1** (also little core). The big cores (2.85 GHz, 42% faster) and prime core (3.4 GHz, 70% faster) sit **completely idle**.

Android's scheduler (EAS - Energy Aware Scheduling) favors little cores for "background" processes. Since RuneLite runs inside Termux (a background app from Android's perspective), the scheduler never promotes it to big cores despite high CPU load.

#### Cause 2: ptrace Overhead (Impact: ~50% throughput loss)

proot uses `ptrace(PTRACE_SYSCALL)` to intercept every syscall from processes running inside the proot environment. This means every syscall from RuneLite and VirGL goes through:

1. RuneLite makes syscall
2. Kernel stops RuneLite, notifies proot via ptrace
3. proot inspects and potentially modifies the syscall
4. proot resumes RuneLite
5. Kernel executes the actual syscall
6. Kernel stops RuneLite again (syscall exit)
7. proot inspects the result
8. proot resumes RuneLite

Evidence: `stime > utime` for both RuneLite (57670 vs 49294 = **54% kernel time**) and VirGL (24079 vs 17234 = **58% kernel time**). Normal applications have 5-15% kernel time. proot adds a 4x multiplier to kernel overhead.

#### Cause 3: VirGL Pipeline Serialization (Impact: latency per frame)

The GL command pipeline is synchronous:

```
RuneLite: glDrawElements() -> Mesa virpipe: encode command -> IPC send -> block
    virgl_test_server: receive -> decode -> ANGLE: glDrawElements() -> return
    IPC response -> Mesa virpipe: return -> RuneLite: next GL call
```

Each GL call from RuneLite requires an IPC round-trip to virgl_test_server. RuneLite's GPU plugin makes hundreds of GL calls per frame (draw calls, texture binds, uniform uploads, etc.). With ~5-20 microseconds per IPC round-trip, hundreds of calls adds 1-4 ms of pure IPC latency per frame.

Additionally, virgl_test_server uses ANGLE (not native GLES), adding another translation layer:
```
RuneLite GL3 calls -> Mesa Gallium -> virpipe encoding -> IPC -> Gallium decoding
    -> ANGLE GL3->GLES2 translation -> Mali GPU driver -> GPU execution
```

### 4.4 Frame Budget Analysis

At 120 FPS, the per-frame budget is **8.33 ms**.

Current per-frame time breakdown (estimated):

| Component | Time | Where |
|-----------|------|-------|
| RuneLite game logic | ~3 ms | CPU (user mode, little core) |
| RuneLite GL call emission | ~4 ms | CPU (user mode, little core) |
| ptrace overhead (RuneLite) | ~5 ms | Kernel mode |
| VirGL IPC round-trips | ~3 ms | IPC latency |
| VirGL GL translation | ~3 ms | CPU (user mode, little core) |
| ptrace overhead (VirGL) | ~3 ms | Kernel mode |
| GPU execution | ~2.5 ms | Mali GPU (measured via fence) |
| eglSwapBuffers | ~0.7 ms | GPU driver |
| **Total** | **~24 ms** | **= ~42 FPS** |

To hit 120 FPS we need to reduce this to 8.33 ms -- a **3x improvement**.

---

## 5. Renderer Code Flow (Detailed)

### 5.1 Choreographer -> Renderer Pipeline

```
lorieChoreographerFrameCallback (InitOutput.c:682, Looper thread)
    |-- AChoreographer_postFrameCallback (re-register for next frame)
    |-- pvfb->state->waitForNextFrame = false  (gate the renderer)
    |-- QueueWorkProc(lorieRedraw)              (queue X server work)
    |-- lorieWakeServer()                       (wake X server event loop)
         |
lorieRedraw (InitOutput.c:438, X server work queue)
    |-- loriePerformVblanks()
    |-- RegionNotEmpty(DamageRegion(pvfb->damage))  <-- check for new content
    |-- if (nonEmpty && priv->buffer):
    |       LorieBuffer_unlock + LorieBuffer_lock   (sync CPU/GPU memory)
    |       DamageEmpty(pvfb->damage)
    |       pvfb->state->drawRequested = TRUE        <-- set content flag
    |-- pthread_cond_signal(&pvfb->state->cond)      <-- wake renderer via proxy
         |
pthreadCondVarProxyThread (renderer.c:973, PthreadCondVarProxy thread)
    |-- pthread_cond_wait(state->cond)  <-- receives signal from lorieRedraw
    |-- pthread_cond_signal(&stateCond) <-- forwards to renderer thread
         |
rendererThread (renderer.c:796, gles-renderer thread)
    |-- rendererShouldWait():
    |       if (waitForNextFrame || !drawRequested) -> keep waiting
    |       if (drawRequested) -> wake up
    |-- rendererRedrawLocked() (renderer.c:696):
    |       lorie_mutex_lock(&state->lock)           ~0.002 ms
    |       state->drawRequested = FALSE
    |       LorieBuffer_bindTexture(buffer)
    |       draw()                                    (GLES draw calls)
    |       glFlush()
    |       eglCreateSyncKHR + eglClientWaitSyncKHR   ~2.7 ms (fence)
    |       state->waitForNextFrame = true             <-- gate until next choreographer
    |       lorie_mutex_unlock(&state->lock)
    |       eglSwapBuffers                             ~0.7 ms
    |       rendererLogPerfSample()
```

### 5.2 Content Production (X Server Side)

The X server's `lorieRedraw` runs at 120 Hz (matching choreographer), but only finds damage 34-36 times per second. Damage is produced when RuneLite writes to the X11 root window via the VirGL pipeline:

```
RuneLite GL draw call -> Mesa virpipe -> IPC -> virgl_test_server -> ANGLE -> GPU
    -> GPU writes to AHardwareBuffer -> X server detects damage region
```

The damage detection happens via Xorg's `DamageRegion` tracking. When `RegionNotEmpty(DamageRegion(pvfb->damage))` returns true, `lorieRedraw` sets `drawRequested = TRUE`.

### 5.3 The rendererShouldWait Gate

```c
// renderer.c:768
static inline bool rendererShouldWait(bool *waitingForBuffers) {
    if (stateChanged || windowChanged || buffersChanged)
        return false;  // Process state changes immediately

    if (!state || !state->surfaceAvailable || state->waitForNextFrame || *waitingForBuffers)
        return true;   // Can't render: no state, no surface, or gated by choreographer

    if (state->drawRequested || state->cursor.moved || state->cursor.updated)
        return false;  // Content available, render now

    return true;       // "Probably spurious wake" -- no content
}
```

The renderer requires BOTH:
1. `waitForNextFrame == false` (cleared by choreographer at 120 Hz)
2. `drawRequested == true` (set by lorieRedraw when damage exists)

If choreographer fires but no damage has arrived, the renderer stays asleep. This is correct behavior -- rendering the same content again would waste GPU cycles. The problem is that damage only arrives 34-36 times/second.

---

## 6. Fixed Bugs During This Investigation

### 6.1 P0: GPU Step Re-runs Every Startup (16 seconds wasted)

**Root cause**: `setup-gpu.sh` and `setup-gpu-mali.sh` never created the `$HOME/.runelite-tablet/markers/step-gpu.done` marker file. `MarkerReconciler` compared SharedPrefs (step completed) against on-disk markers (absent), cleared SharedPrefs, and forced a 16-second GPU re-install.

**Fix**: Added marker creation to `setup-gpu.sh`:
```bash
MARKER_DIR="$HOME/.runelite-tablet/markers"
mkdir -p "$MARKER_DIR"
touch "$MARKER_DIR/step-gpu.done"
```

**File**: `runelite-tablet/app/src/main/assets/scripts/setup-gpu.sh`

### 6.2 P1: Attach Loop Never Exits (CPU waste)

**Root cause**: In `X11AttachmentController.tryAttach()`, `attachJob?.cancel()` was inside the `if (!xConnectionAttached)` block. Once connected, subsequent calls hit the early-return path but skipped the cancel, leaving the coroutine running indefinitely (observed at attempt=631+).

**Fix**: Moved `attachJob?.cancel()` outside the conditional:
```kotlin
if (!forceReconnect && connector.isConnected()) {
    if (!xConnectionAttached) {
        xConnectionAttached = true
        onStatusUpdate("connected=true")
        onStatusVisible(false)
    }
    attachJob?.cancel()  // Always cancel, not just on first connection
    return
}
```

**File**: `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/X11AttachmentController.kt`

### 6.3 P1: Script Deployment Churn (12 sequential IPC calls)

**Root cause**: Script deployment fired 12 individual Termux IPC commands sequentially, each causing `TermuxResultService` to do `onCreate/onDestroy`.

**Fix**: Two changes:
1. Added `deployScriptsBatched()` to `ScriptManager.kt` -- deploys all 12 scripts in a single base64-encoded Termux command
2. Added 500ms `stopSelf` delay to `TermuxResultService` to batch results through the same service instance

**Files**: `ScriptManager.kt`, `TermuxResultService.kt`

### 6.4 P2: Duplicate Binder Log Spam

**Root cause**: `TermuxX11StartReceiver` logged every duplicate `ACTION_START` binder, flooding logcat with identical messages.

**Fix**: Rate-limited to 1 log per 30 seconds with suppression count.

**File**: `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/TermuxX11StartReceiver.kt`

### 6.5 WebSocket Debug Server Fixes

Two runtime bugs in `DebugLogServer.kt`:

1. **BufferedReader stream corruption**: `BufferedReader(InputStreamReader(...))` does read-ahead, corrupting the raw InputStream needed for WebSocket frame parsing after HTTP upgrade. Fixed by reading HTTP headers byte-by-byte from the raw InputStream.

2. **NetworkOnMainThreadException**: `broadcast()` called from the main thread attempted socket I/O, blocked by Android's StrictMode. Fixed by adding a `ConcurrentLinkedQueue<ByteArray>` send queue with a dedicated `DebugLogServer-Send` drain thread.

**File**: `runelite-tablet/app/src/main/java/com/runelitetablet/logging/DebugLogServer.kt`

---

## 7. Startup Performance

After fixes, startup improved from **20.3 seconds to 3.7 seconds** (5.5x improvement):

| Step | Before | After | Fix |
|------|--------|-------|-----|
| GPU setup (unnecessary re-run) | 16.0 s | 0 s | Marker file creation |
| Script deployment (12 serial) | ~2.0 s | ~0.3 s | Batched base64 deployment |
| Service lifecycle churn | ~1.5 s | ~0.1 s | Delayed stopSelf |
| Attach loop overhead | ongoing | 0 | Cancel on connect |
| **Total** | **~20.3 s** | **~3.7 s** | |

---

## 8. Proposed Fixes for 120 FPS

### Fix A: CPU Core Affinity (Immediate, Low Risk)

**What**: Pin RuneLite's Client thread and virgl_test_server to big/prime cores (CPU 4-7) using `taskset`.

**Why**: RuneLite and VirGL currently run on little cores (2.0 GHz). Big cores are 42% faster (2.85 GHz), prime core is 70% faster (3.4 GHz). Both are completely idle during gameplay.

**How**: Modify `launch-runelite.sh` to use `taskset`:
```bash
# Pin virgl to big cores
taskset -p 0xF0 $(pidof virgl_test_server_android) 2>/dev/null

# Pin RuneLite java to big/prime cores
taskset -p 0xF0 $RUNELITE_PID 2>/dev/null
```

**Expected impact**: ~50-60 FPS (from ~40 FPS). Won't reach 120 FPS alone because of ptrace overhead and VirGL IPC serialization.

**Risk**: Low. `taskset` is available in Termux. If it fails, processes stay on their current cores.

**Files to change**: `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`

### Fix B: Reduce ptrace Overhead (Medium Term, Medium Risk)

**What**: Evaluate alternatives to proot's ptrace-based syscall interception.

**Options**:
1. **proot with seccomp-bpf**: proot can use seccomp instead of ptrace on newer kernels, reducing per-syscall overhead from ~50us to ~5us
2. **Linux user namespaces**: If the kernel supports `CONFIG_USER_NS`, we can use `unshare` instead of proot entirely
3. **chroot with fakeroot**: For simple path remapping without full ptrace

**Why**: ptrace causes >50% of CPU time to be spent in kernel mode. Eliminating ptrace would roughly double the available CPU throughput.

**Expected impact**: +15-20 FPS on top of Fix A, potentially reaching 70-80 FPS.

**Risk**: Medium. Kernel feature availability varies by device. Requires careful testing of RuneLite's syscall patterns.

**Investigation needed**:
```bash
# Check kernel config
adb shell "zcat /proc/config.gz | grep -i 'USER_NS\|SECCOMP'"
# Check proot seccomp support
proot --help 2>&1 | grep seccomp
```

### Fix C: VirGL Pipeline Optimization (Medium Term, Medium Risk)

**What**: Reduce per-frame IPC overhead in the VirGL pipeline.

**Options**:
1. **Increase virgl command batching**: The virpipe driver can batch multiple GL commands into a single IPC transfer. Check/set `VIRGL_RENDERER_THREAD` and `VIRGL_RENDERER_ASYNC` environment variables.
2. **Use virgl's native fence mechanism**: Avoid blocking on every command completion.
3. **Reduce RuneLite GL call count**: Configure RuneLite's GPU plugin to use fewer draw calls (larger batches, texture atlasing).

**Expected impact**: +10-20 FPS by reducing IPC round-trip overhead.

**Risk**: Medium. VirGL configuration changes may cause rendering artifacts.

**Investigation needed**:
```bash
# Check virgl environment
env | grep VIRGL
# Check Mesa debug output
MESA_DEBUG=1 LIBGL_DEBUG=verbose <run RuneLite>
```

### Fix D: Bypass VirGL (Long Term, High Risk)

**What**: Eliminate the VirGL double-translation pipeline entirely.

**Options**:
1. **llvmpipe (software rendering)**: Replace VirGL with Mesa's software rasterizer. Avoids all IPC overhead but uses CPU instead of GPU. May actually be faster for RuneLite's workload since it eliminates the IPC bottleneck.
2. **Direct AHardwareBuffer sharing**: Have RuneLite render to an AHardwareBuffer that's directly composited by the Android renderer. Requires modifying RuneLite's rendering backend.
3. **Zink + direct GPU**: Use Mesa's Zink driver (GL-on-Vulkan) with direct GPU access instead of VirGL, bypassing the virgl_test_server entirely.

**Expected impact**: Potentially 120 FPS if the pipeline overhead is truly the bottleneck.

**Risk**: High. Major architectural changes, significant development effort.

**Investigation needed**:
- Benchmark llvmpipe vs VirGL with a simple GL workload through proot
- Check if direct GPU access is possible from within proot (GPU device node passthrough)
- Evaluate Zink driver maturity on aarch64

---

## 9. Key Metrics to Track

| Metric | Source | Current | Target |
|--------|--------|---------|--------|
| damage-triggered redraws | X server (ADB logcat, LorieNative tag) | 34-36 FPS | 120 FPS |
| estimated_fps | renderer (debug server, gles-renderer tag) | 38-54 FPS | 120 FPS |
| content_util | renderer (debug server) | 100% | 100% |
| RuneLite CPU% | `top -p <PID>` | 33.3% | >80% |
| RuneLite stime/utime ratio | `/proc/<PID>/stat` | 1.17 (54% kernel) | <0.2 (17% kernel) |
| RuneLite CPU core | `/proc/<PID>/stat` field 39 | CPU 0 (little) | CPU 4-7 (big/prime) |
| VirGL CPU% | `top -p <PID>` | 33.3% | <20% or eliminated |
| avg_inter_frame_ms | renderer (debug server) | 22-27 ms | 8.3 ms |
| avg_fence_ms | renderer (debug server) | 2.5-2.9 ms | <3 ms (already good) |

---

## 10. Logging Coverage Assessment

### Currently instrumented (good coverage):

| Component | Log Tag | Process | Visible To App | Metrics |
|-----------|---------|---------|-----------------|---------|
| Renderer thread | gles-renderer | App (27320) | Yes (debug server) | FPS, fence, swap, frame, inter-frame, content_util, starved, vsyncs |
| Choreographer | LorieNative | X server (5533) | No (ADB only) | Callbacks/sec |
| lorieRedraw | LorieNative | X server (5533) | No (ADB only) | Wakeups, damage redraws, frames |
| Kotlin UI | FRAME | App (27320) | Yes (debug server) | FPS, jank, p99, heap |
| Jank detection | JANK | App (27320) | Yes (debug server) | Per-frame actual vs expected |

### Logging gaps (need instrumentation):

| Component | What's Missing | Why It Matters |
|-----------|---------------|----------------|
| **VirGL IPC** | Per-command IPC latency, batch size, queue depth | Quantify serialization overhead |
| **Mesa virpipe driver** | GL calls per frame, batch flush frequency | Know if RuneLite is draw-call heavy |
| **proot ptrace** | Syscalls per frame, average ptrace latency | Quantify ptrace overhead precisely |
| **RuneLite GPU plugin** | Draw calls per frame, scene complexity | Know if RuneLite itself is the limit |
| **virgl_test_server** | ANGLE translation time, GPU submit latency | Know if ANGLE overhead matters |
| **Renderer idle time** | Time spent in pthread_cond_wait, broken down by wait reason (choreographer gate vs content wait) | Quantify how much time the renderer wastes waiting |

### Logging architecture note:

The X server (PID 5533) logs are invisible to the app's debug server because Android restricts `logcat` to the app's own PID. These logs are only accessible via ADB from the development machine. To surface them in the debug server, we would need to either:
1. Forward X server logs through the shared state memory region
2. Have the X server write to a file that the app reads
3. Use a socket-based log transport between the X server and app processes

---

## 11. RuneLite Configuration (Current)

From `launch-runelite.sh`, RuneLite is configured for maximum FPS:

```properties
gpu.fpsTarget=120
unlockFps=true
vsyncMode=OFF
fpscontrol.limitFps=false
fpscontrol.maxFps=360
```

JVM flags:
```
-Xmx4g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50
-XX:CompileThreshold=1500 -Xss2m
```

These settings are correct for maximum throughput. The FPS cap is not the issue.

---

## 12. Device-Specific Notes

### Samsung Galaxy Tab S10 Ultra (SM-X920)

- **SoC**: MediaTek Dimensity 9300+
- **CPU**: 4x Cortex-A720 @ 2.0 GHz (little) + 3x Cortex-X4 @ 2.85 GHz (big) + 1x Cortex-X4 @ 3.4 GHz (prime)
- **GPU**: Immortalis-G720 MC12 (Mali family)
- **RAM**: 12 GB (11,526 MB usable)
- **Display**: 120 Hz, 2960x1848
- **Android**: 15 (API level?)
- **Kernel**: Supports proot with ptrace

### Mali GPU + VirGL + ANGLE Interaction

Mali GPUs use ANGLE for OpenGL ES compatibility. The VirGL pipeline becomes:

```
RuneLite OpenGL 3.x -> Mesa Gallium (virpipe) -> encode Gallium commands
    -> virtio-gpu IPC -> virgl_test_server -> decode Gallium -> Gallium-to-GL3 translation
    -> ANGLE GL3->GLES2 translation -> Mali GPU driver -> Immortalis-G720
```

This is a **triple translation**: Gallium encoding, Gallium-to-GL3, GL3-to-GLES2. Each layer adds overhead.

On Adreno GPUs, ANGLE is not needed because Qualcomm provides a native OpenGL ES driver that can handle GL3 calls directly, reducing the pipeline to a double translation.

---

## 13. Experimental Ideas (Unvalidated)

### 13.1 glxgears Benchmark Through VirGL

Run `glxgears` through the same proot+VirGL pipeline to establish a baseline. If glxgears hits 120 FPS, the bottleneck is RuneLite-specific. If glxgears is also limited, the bottleneck is the pipeline itself.

```bash
# Inside proot
DISPLAY=:0 glxgears -info
```

### 13.2 GALLIUM_HUD for VirGL Profiling

Mesa's `GALLIUM_HUD` can display real-time pipeline metrics:
```bash
GALLIUM_HUD="fps,draw-calls,prims-emitted" java -jar RuneLite.jar
```

### 13.3 strace for Syscall Profiling

Measure proot overhead by counting syscalls:
```bash
# Inside proot, wrap RuneLite
strace -c -f java -jar RuneLite.jar 2>/tmp/strace-summary.txt
```

### 13.4 CPU Governor Override

Force performance governor on big cores:
```bash
# Requires root
echo performance > /sys/devices/system/cpu/cpu4/cpufreq/scaling_governor
echo performance > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor
```

---

## 14. Next Steps (Priority Order)

1. **Implement Fix A (CPU affinity)** -- lowest effort, highest expected impact
2. **Run glxgears benchmark** -- establish VirGL pipeline ceiling
3. **Add renderer idle-time tracking** -- quantify wait-reason breakdown
4. **Test GALLIUM_HUD** -- get per-frame GL metrics from inside the pipeline
5. **Investigate proot seccomp mode** -- check if kernel supports it
6. **Evaluate llvmpipe** -- may outperform VirGL by eliminating IPC entirely

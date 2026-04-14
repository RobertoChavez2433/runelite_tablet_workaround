# Presentation Pipeline 120 FPS Optimization Spec

**Date**: 2026-03-16
**Branch**: `spike/direct-android-surface`
**Baseline**: ~50 FPS sustained (brief bursts to 120 FPS)
**Target**: 90-120 FPS sustained
**Device**: Samsung Tab S10 Ultra, Immortalis-G720 MC12, 120Hz display, 12GB RAM

## Problem Statement

RuneLite running via Termux+proot achieves only ~50 FPS sustained despite the hardware being capable of 120 FPS (proven by app-owned SurfaceView probe). The bottleneck is the presentation pipeline between VirGL rendering and the Android display surface.

### Current Pipeline (per frame)

```
RuneLite (Java/AWT) → glXSwapBuffers
  → Mesa virpipe flush_frontbuffer()
    → VCMD_TRANSFER_GET2 (socket write to VirGL server)
    → virgl_test_server: glReadPixels() [GPU→CPU readback, 2-5ms]
    → VCMD_BUSY_WAIT (synchronous socket round-trip, 0.1-1ms)
    → util_copy_rect() [CPU memcpy shm→displaytarget, 1-2ms]
    → displaytarget_display() [X11 ShmPutImage, 1-2ms]
  → Xlorie lorieRedraw() [damage check, buffer unlock/lock]
    → rendererRedrawLocked()
      → glTexSubImage2D() [CPU→GPU re-upload, 5-15ms — LEGACY PATH]
      → OR glEGLImageTargetTexture2DOES() [zero-copy — IF working]
      → eglSwapBuffers()
      → waitForNextFrame = true [BLOCKS until next choreographer, 6ms waste]
  → Android SurfaceView → SurfaceFlinger → Display
```

**Total per-frame presentation overhead**: 15-25ms (at 50 FPS = 20ms budget)

### Root Causes Identified

| # | Bottleneck | Cost/Frame | Location |
|---|-----------|-----------|----------|
| 1 | VirGL `glReadPixels` readback | 2-5ms | `virgl_vtest_flush_frontbuffer()` in Mesa |
| 2 | VirGL synchronous busy-wait | 0.1-1ms | `virgl_vtest_busy_wait()` in Mesa |
| 3 | CPU memcpy shm→displaytarget | 1-2ms | `util_copy_rect()` in Mesa |
| 4 | X11 ShmPutImage | 1-2ms | `displaytarget_display()` in Mesa |
| 5 | Xlorie legacy `glTexSubImage2D` | 5-15ms | `buffer.c:427` — **active on Mali** |
| 6 | Xlorie `waitForNextFrame` stall | ~6ms | `renderer.c:662` + choreographer gap |
| 7 | Post-swap fence wait dead time | ~4ms | `renderer.c:670-678` |

## Hard Constraints

- No rooting the tablet
- No modifying RuneLite source (third-party Java app)
- Must stay on proot (no `/dev/dri` kernel driver access)
- Mali/Immortalis GPU (no Turnip/Zink — Adreno-only)
- VirGL vtest is the only available GPU virtualization path
- Must preserve input (mouse/keyboard/touchpad) and auth (Jagex OAuth)
- All Xlorie native code is vendored and buildable in-app

## Success Criteria

- Sustained FPS >= 90 on real RuneLite logged-in session (measured by `LorieNative` + `queueBuffer` stats)
- No regression in input latency or auth flow
- Each phase independently measurable with existing evidence harness
- All changes in vendored native code or app-side Kotlin (no Termux package rebuilds required for Phase 1-2)

---

## Phase 1: Fix Legacy Drawing Fallback (RGBA Flip Path)

**Expected gain**: +10-15 FPS (eliminate per-frame `glTexSubImage2D` upload)
**Risk**: Low — code path already exists, just needs format validation on Mali
**Effort**: Small — ~20 lines of native C change + diagnostic

### Problem

`rendererTestCapabilities()` (`renderer.c:350-458`) tests BGRA `AHardwareBuffer` + `eglCreateImageKHR`. On Mali, this fails with `EGL_BAD_PARAMETER` (Mali doesn't support BGRA EGL images). The existing code then checks for `EGL_BAD_PARAMETER` specifically and sets `flip=1` + retries with `AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM`.

**But**: If the second attempt also fails (or the readback verification fails), it falls all the way to `legacy_drawing=1`. The log shows `"Forcing legacy drawing"`, meaning the RGBA retry path is NOT succeeding on this device.

### Solution

1. **Instrument `rendererTestCapabilities()`** to log which specific step fails on Mali:
   - Does `AHardwareBuffer_allocate()` succeed for `R8G8B8X8_UNORM`?
   - Does `eglGetNativeClientBufferANDROID()` succeed?
   - Does `eglCreateImageKHR()` succeed for the RGBA buffer?
   - Does the readback pixel verification pass?

2. **If RGBA EGLImage works but readback check fails**: The pixel verification at line 443-450 compares against expected BGRA→RGBA swizzle result. On Mali, the pixel may come back in a different order. Add Mali-specific expected values or relax the check when `flip=1`.

3. **If RGBA EGLImage creation itself fails**: The existing code retries with `R8G8B8X8_UNORM` (value 2), but this maps to `GL_RGB8` in GLES — Mali may reject this for EGLImage import. **Try `R8G8B8A8_UNORM` (value 1, maps to `GL_RGBA8`) instead.** This is the universally supported RGBA format on Mali and the correct format for Phase 3's AHardwareBuffer sharing.

4. **Verify the `.bgra` shader swizzle works**: The fragment shader at `renderer.c:110` does `gl_FragColor = texture2D(texture, outTexCoords).bgra;` — this is a free GPU operation but needs to produce correct colors.

### Files Modified

- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c` — `rendererTestCapabilities()` diagnostic + RGBA fix
- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/buffer.c` — Verify `LorieBuffer_attachToGL()` handles RGBA format

### Measurement

- Before: `LorieNative` logs show `"Forcing legacy drawing"` + `glTexSubImage2D` path active
- After: Logs show `"flip=1"` + `EGLImageKHR` path active, no `"legacy drawing"` message
- FPS delta measured via existing `hybrid-x11-runelite-evidence.ps1` harness

---

## Phase 2: Remove `waitForNextFrame` 2-Vsync Cap

**Expected gain**: +10-20 FPS (remove artificial frame pacing stall)
**Risk**: Low-Medium — changes frame pacing model, needs testing for jank
**Effort**: Small — ~15 lines of native C change

### Problem

After `eglSwapBuffers`, renderer sets `waitForNextFrame=true` (renderer.c:662). It then sleeps in `pthread_cond_wait` until the next choreographer callback clears the flag (InitOutput.c:413). At 120Hz this wastes ~6ms per frame. Additionally, the post-swap "prime next buffer" operation (lines 670-678) does a synchronous `eglClientWaitSyncKHR(EGL_FOREVER)` that adds ~4ms dead time.

Combined: ~10ms of dead wait per frame on a device where the actual render takes ~5ms.

### Solution

**Option A (Conservative)**: Remove only the post-swap fence wait

In `renderer.c`, remove lines 670-678 (the `glClear` + `eglCreateSyncKHR` + `eglClientWaitSyncKHR` + `eglDestroySyncKHR` block). This saves ~4ms without changing the frame pacing model.

**Option B (Aggressive — Recommended)**: Remove `waitForNextFrame` entirely

1. In `rendererRedrawLocked()` (renderer.c:662): Remove `state->waitForNextFrame = true;`
2. In `rendererShouldWait()` (renderer.c:701): Remove `state->waitForNextFrame` from the wait condition
3. Remove post-swap fence wait block (lines 670-678)
4. Keep `eglSwapInterval(egl_display, 0)` (already set)
5. Add `eglPresentationTimeANDROID()` before `eglSwapBuffers` with next vsync timestamp for proper frame pacing hints

This lets the renderer run as fast as damage arrives. Android's BufferQueue naturally provides backpressure when the queue is full (with `eglSwapInterval(0)`).

**Risk mitigation**: If Option B causes excessive GPU waste or jank, fall back to Option A.

### Files Modified

- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c` — Remove `waitForNextFrame` set + post-swap block
- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c` — Optional: remove `waitForNextFrame = false` clear (dead code if flag removed)

### Measurement

- Before: `avg_inter_frame_ms` ~20ms, `choreographer callbacks = 120 FPS` but `LorieNative` ~50 FPS
- After: `avg_inter_frame_ms` should drop to ~8-10ms, `LorieNative` should approach damage cadence
- Use existing `XloriePerf` timing buckets in the evidence harness

---

## Phase 3: AHardwareBuffer Zero-Copy VirGL Bypass

**Expected gain**: +20-40 FPS (eliminate readback + socket transfer + re-upload)
**Risk**: High — requires native C changes to vendored Xlorie code + custom VirGL server control protocol
**Effort**: Large — ~500 lines of native C across multiple files

### Problem

Every frame, VirGL's `flush_frontbuffer()` does a full GPU→CPU→socket→CPU→GPU round-trip:
1. `glReadPixels()` on server (GPU→CPU, forces Mali tile resolve, 2-5ms)
2. Socket round-trip for busy-wait (0.1-1ms)
3. `util_copy_rect()` CPU memcpy (1-2ms)
4. X11 ShmPutImage (1-2ms)
5. Xlorie re-uploads via `glTexSubImage2D` or binds via EGLImage

At 2960x1848 RGBA, each readback transfers ~22MB through CPU memory. This kills AFBC compression and wastes memory bandwidth.

### Solution: Shared AHardwareBuffer Rendering

**Architecture**:

```
┌──────────────────┐     AHardwareBuffer (GPU VRAM, AFBC)      ┌──────────────────┐
│ virgl_test_server │ ←═══════════════════════════════════════► │ Xlorie renderer  │
│ (render into AHB  │     Allocated once, fd shared via socket  │ (sample AHB as   │
│  via FBO attach)  │                                           │  EGLImage texture)│
│                   │     fence fd (SCM_RIGHTS, ~100 bytes)     │                  │
│                   │ ──────────────────────────────────────────►│                  │
└──────────────────┘                                            └──────────────────┘
        ▲                                                              │
        │ virgl cmds (existing socket)                                 │ SurfaceView
        │                                                              ▼
┌──────────────────┐                                            ┌──────────────────┐
│ Mesa virpipe      │                                            │ Android Display   │
│ (inside proot)    │                                            │ (SurfaceFlinger)  │
└──────────────────┘                                            └──────────────────┘
```

**Implementation steps**:

#### 3a. Allocator — Xlorie side

In the Xlorie renderer (our vendored `renderer.c` / `activity.c`):

1. Allocate 2 `AHardwareBuffer`s (double buffer) with:
   ```c
   AHardwareBuffer_Desc desc = {};  // zero-init required (rfu0, rfu1 must be 0)
   desc.width = screenWidth;
   desc.height = screenHeight;
   desc.layers = 1;
   desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;  // value 1, GL_RGBA8
   desc.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER     // 0x200 — FBO attachment
              | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;   // 0x100 — texture sampling
   ```
   - NO `CPU_READ`/`CPU_WRITE` flags (preserves AFBC compression on Mali)
   - RGBA format (Mali-compatible, verified on Immortalis-G720)

2. Send AHB handles to VirGL server via existing Unix socket connection using `AHardwareBuffer_sendHandleToUnixSocket()`

3. Import each AHB as an `EGLImageKHR` for texture sampling:
   ```c
   EGLClientBuffer cb = eglGetNativeClientBufferANDROID(ahb);
   EGLImageKHR img = eglCreateImageKHR(display, EGL_NO_CONTEXT,
       EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
   glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, img);
   ```

#### 3b. Receiver — VirGL server side

In our in-app `CmdEntryPoint` (the internal-hybrid path runs the server in our process):

1. Receive AHB handles via `AHardwareBuffer_recvHandleFromUnixSocket()`
2. Import as EGLImage and attach to FBO as color attachment:
   ```c
   glGenFramebuffers(1, &scanoutFbo);
   glBindFramebuffer(GL_FRAMEBUFFER, scanoutFbo);
   glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texId, 0);
   ```
3. Redirect the scanout resource's render target to this FBO
4. After each frame's `glFlush()`, create a native fence and send the fd:
   ```c
   EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, ...);
   int fenceFd = eglDupNativeFenceFDANDROID(display, sync);
   // send fenceFd via sendmsg(SCM_RIGHTS)
   ```

#### 3c. Synchronization — Xlorie compositor

1. Receive fence fd from VirGL server
2. Import as GPU-side wait (zero CPU cost):
   ```c
   EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID,
       {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fenceFd, EGL_NONE});
   eglWaitSyncKHR(display, sync, 0);  // GPU-side wait, no CPU stall
   ```
3. Sample the AHB texture and composite (cursor overlay + present)
4. Swap the double-buffer index for the next frame

#### 3d. Control channel

A lightweight out-of-band control socket between Xlorie and VirGL server for:
- AHB handle exchange at startup
- Fence fd passing per frame
- Buffer index signaling (which of the 2 AHBs is current)
- Resize events (reallocate AHBs)

This is NOT a modification to the vtest protocol itself. It's a sideband channel between our app's Xlorie renderer and our in-app CmdEntryPoint server.

### What this eliminates

| Step | Before | After |
|------|--------|-------|
| `glReadPixels` on server | 2-5ms | **Eliminated** |
| Socket busy-wait | 0.1-1ms | **Eliminated** (fence fd instead) |
| CPU memcpy shm→dt | 1-2ms | **Eliminated** |
| X11 ShmPutImage | 1-2ms | **Eliminated** |
| Xlorie `glTexSubImage2D` | 5-15ms | **Eliminated** (EGLImage sample) |
| **Total saved** | **10-25ms/frame** | **~0.1ms** (GPU fence wait) |

### Files Modified

- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c` — AHB allocation, import, fence receive
- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/activity.c` — Control channel setup
- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/buffer.c` — New AHB buffer type
- `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java` — AHB receive, FBO redirect, fence export
- New file: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/ahb_bridge.c` — AHB sharing protocol

### Measurement

- Before: `avg_frame_ms` ~5ms but `avg_inter_frame_ms` ~20ms (pipeline stall)
- After: `avg_inter_frame_ms` should approach `avg_frame_ms` (~5-8ms)
- Validate zero readback via `strace` (no `glReadPixels` calls on server)
- Monitor AFBC status via GPU profiler if available

### Prerequisites

- Phase 1 must be complete (RGBA EGLImage path validated on Mali)
- Internal-hybrid path must be the active variant (server runs in our process)

---

## Phase 4: Hybrid Host Lifecycle Parity

**Expected gain**: Correctness + potential 5-10 FPS from proper renderer initialization
**Risk**: Low — well-understood upstream behavior
**Effort**: Small — ~50 lines of Kotlin

### Problem

`HybridX11HostActivity` is missing three upstream `MainActivity` calls after X connection:
1. `clientConnectedStateChanged()` — notifies native renderer of connection
2. `reloadPreferences(prefs)` — configures display/input preferences
3. `SharedPreferences` change listener — responds to dynamic preference updates

### Solution

1. After `LorieView.connect(detached)` + `triggerCallback()` in `tryAttach()`:
   ```kotlin
   // Add missing upstream lifecycle calls
   lorieView.reloadPreferences(getDefaultSharedPreferences())
   ```

2. Register a `SharedPreferences.OnSharedPreferenceChangeListener` in `onCreate()`

3. Add a `clientConnectedStateChanged()` equivalent that updates UI visibility and input handler state

### Files Modified

- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt`
- `runelite-tablet/app/src/main/java/com/termux/x11/LorieView.kt` — Ensure `reloadPreferences()` is exposed

### Measurement

- Verify no `"Forcing legacy drawing"` regression after preference reload
- Confirm renderer receives proper display configuration (resolution, refresh rate)

---

## Phased Execution Order

```
Phase 1 (Fix Legacy Drawing)
  ↓ Measure: expect ~60-65 FPS
Phase 2 (Remove waitForNextFrame)
  ↓ Measure: expect ~70-80 FPS
Phase 4 (Hybrid Lifecycle Parity)
  ↓ Measure: expect ~75-85 FPS (or validates no regression)
Phase 3 (AHardwareBuffer Zero-Copy)
  ↓ Measure: expect ~90-120 FPS
```

Phase 4 is ordered before Phase 3 because it's low-effort/low-risk and ensures the renderer is properly initialized before the zero-copy work.

## Affected Packages

- `presentation/hybrid` — Host activity lifecycle
- `third_party/termux-x11-upstream` — Vendored Xlorie native renderer
- `assets/scripts` — Launch script (if VirGL server args change)

## Constraint References

- `architecture-decisions/presentation-constraints.md` — Hybrid host design constraints
- `defects/_defects-shell.md` — GL_DEPTH_CLAMP, MESA_EXTENSION_OVERRIDE patterns
- `research/gpu-rendering-options.md` — Mali GPU path constraints
- `research/virgl-capabilities-dump.md` — Missing GL_ARB_clip_control

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| RGBA EGLImage fails on Mali | Medium | Phase 1 blocked | Try `R8G8B8A8_UNORM` instead of `R8G8B8X8_UNORM`; if all fail, Phase 1 becomes diagnostic-only |
| Removing waitForNextFrame causes jank | Low | Visual quality | Fall back to Option A (remove only post-swap fence) |
| AHB sharing between processes fails | Low | Phase 3 blocked | Internal-hybrid runs server in same process; cross-process isn't needed |
| Fence fd not supported on Mali | Very Low | Phase 3 sync broken | Fall back to `eglClientWaitSyncKHR` (CPU-side wait, still faster than readback) |
| Total FPS < 90 after all phases | Medium | Goal not met | Accept ~70-80 FPS as practical ceiling for VirGL on Mali |

## API Audit Results (Context7 + Web Verified 2026-03-16)

All APIs referenced in this spec have been verified against current Android NDK documentation, Khronos specs, and device-specific extension support. Summary:

### All APIs Confirmed Current — No Deprecations

| API | Extension | Min API | Status |
|-----|-----------|---------|--------|
| `AHardwareBuffer_sendHandleToUnixSocket` | NDK | 26 | Current, no replacement exists |
| `AHardwareBuffer_recvHandleFromUnixSocket` | NDK | 26 | Current, no replacement exists |
| `eglCreateSyncKHR` (native fence) | `EGL_ANDROID_native_fence_sync` | 17 | Current |
| `eglDupNativeFenceFDANDROID` | `EGL_ANDROID_native_fence_sync` | 17 | Current |
| `eglWaitSyncKHR` | `EGL_KHR_wait_sync` | 17 | Current (prefer over EGL 1.5 `eglWaitSync` for compat) |
| `eglPresentationTimeANDROID` | `EGL_ANDROID_presentation_time` | 18 | Current, time base = `CLOCK_MONOTONIC` |
| `eglGetNativeClientBufferANDROID` | `EGL_ANDROID_get_native_client_buffer` | 26 | Current |
| `glEGLImageTargetTexture2DOES` | `GL_OES_EGL_image` | Any GLES | Current (newer `TexStorageEXT` exists but does NOT replace it) |

### Extension Availability on Immortalis-G720

`EGL_ANDROID_native_fence_sync` is **confirmed supported** on:
- Native ARM Mali GLES driver (mandatory for Android CDD compliance)
- ANGLE Vulkan backend (implemented via `VK_KHR_external_fence_fd`)

### Corrections Applied to Spec

1. **`AHardwareBuffer_Desc` struct**: Has additional fields `.stride` (output-only), `.rfu0`, `.rfu1` (reserved). Must zero-initialize the full struct before setting fields. C designated initializers handle this automatically.

2. **`AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM`**: This is NOT an official NDK constant. The `#define` in Xlorie's `buffer.h` with value `5` maps to `HAL_PIXEL_FORMAT_BGRA_8888` — a HAL-level format. Mali does NOT support BGRA for EGLImage import, which is why the spec's Phase 1 targets RGBA.

3. **`AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM` (value 2)**: Maps to `GL_RGB8` in OpenGL ES (NOT `GL_RGBA8`). This mismatch may be why the existing Xlorie `flip=1` retry path fails on Mali — the driver may reject `GL_RGB8` for EGLImage import. **Phase 1 should try `R8G8B8A8_UNORM` (value 1, maps to `GL_RGBA8`) FIRST**, not `R8G8B8X8_UNORM`.

4. **AFBC preservation**: Confirmed correct. Omitting `CPU_READ`/`CPU_WRITE` flags allows Mali Gralloc to use AFBC. Immortalis-G720 is Valhall 5th-gen with broadest AFBC support.

5. **Fence FD ownership**: `eglCreateSyncKHR` takes ownership of the passed FD — caller must NOT close it after the call. `eglDupNativeFenceFDANDROID` returns a new FD — caller IS responsible for closing it.

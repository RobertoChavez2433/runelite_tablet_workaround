# Direct-Android-Surface De-Risker Result (S82 Task #25)

**Date**: 2026-04-26
**Probe**: `scripts/cross-process-surface-probe.c` (~370 LoC) + `scripts/build-cross-process-probe.sh`
**Device**: Tab S10 Ultra (R52X90378YB), Mali-G720-Immortalis MC12, driver r44p1, EGL 1.5 Android META-EGL
**Verdict**: **PASS — 5a primitive is viable on this stack.**

## What we actually tested

The scope doc (`direct-android-blocker-3-scope.md`) called for a peer process
to receive a SurfaceView's buffer-queue FD and rebuild an `ANativeWindow*`.
Pure C without a JVM can't construct a Java `Surface`, so we tested the
**underlying primitive** that any cross-process Surface handoff would lean on:

> Cross-process import of an `AHardwareBuffer` as an EGLImage via
> `AHardwareBuffer_sendHandleToUnixSocket` (SCM_RIGHTS) +
> `eglGetNativeClientBufferANDROID` + `eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID)`.

ANativeWindow's BufferQueue produces AHBs internally; if Mali's stack rejects
cross-process AHB import, the larger Surface-AIDL leg is dead before we start.
This was the cheapest binary yes/no.

## Design

`fork()` into producer (parent) + consumer (child); communicate via
`socketpair(AF_UNIX, SOCK_STREAM)`. Each iteration:

1. **Producer**: allocate AHB at varied W,H (16-aligned, 256–768 × 256–640 to
   simulate resize); create EGLImage; render triangle to FBO with cycling
   colour (red→green→blue per iter); `glFinish`; send AHB handle via
   `AHardwareBuffer_sendHandleToUnixSocket`; wait verdict.
2. **Consumer**: `recv` AHB via `AHardwareBuffer_recvHandleFromUnixSocket`;
   import to EGLImage in its own EGLContext; bind to FBO; `glReadPixels` at
   centre; verify colour matches the producer's iter-encoded expectation.
3. Track FD count via `/proc/self/fd` at start, mid, end on both sides.

End-of-stream uses a 5-int sentinel (`iter == -1`) peeked via `MSG_PEEK` so
neither side miscounts the sentinel as a recv failure.

## Results

100 iterations:

| Metric | Producer | Consumer |
|---|---|---|
| Iterations completed | 100 | 100 |
| AHB allocate fails | 0 | n/a |
| EGLImage import fails | 0 | 0 |
| Colour verification ok | n/a | 100 |
| Colour verification fail | n/a | 0 |
| recv_fail (post-fix) | n/a | 0 |
| FD start | 14 | 14 |
| FD mid (after iter ~50) | 17 | 17 |
| FD end | 17 | 17 |
| Net FD leak per iter | **0** | **0** |

The `14 → 17` delta is the 3 persistent FDs (EGL display, socket pair end,
etc.) that don't grow with iteration count.

JSON: `docs/s82-capture/cross-process-surface-probe.json`
Stderr log: `docs/s82-capture/cross-process-surface-probe.stderr.log`

## What this clears, what it doesn't

**Clears:**
- Mali r44p1 driver accepts cross-process AHB import via SCM_RIGHTS — no
  vendor lock-down or stub on this device.
- `EGL_ANDROID_get_native_client_buffer` + `EGL_ANDROID_image_native_buffer`
  + `GL_OES_EGL_image` work end-to-end across processes.
- AHB allocate / release lifecycle is clean across 100 size-varied cycles
  with zero FD leak — the resize concern in the scope doc's "Top 3 risks" is
  not a per-iteration leak risk on this primitive.
- EGL_KHR_surfaceless_context lets both sides run without a window surface,
  so the JVM-side rlawt won't need a dummy pbuffer just to bring up its
  context.

**Does NOT clear:**
- Java `Surface` ↔ NDK Binder marshaling (the `Surface.writeToParcel` /
  `ANativeWindow_fromSurface` half of the handoff).
- `ANativeWindow_setBuffersGeometry` / SurfaceView resize callback chain
  reaching the JVM-side EGL surface in time to recreate.
- BGRA vs RGBA colour-order mismatch when the producer is the LorieView
  Surface (which forces `BGRA_8888`) and the consumer is the JVM rlawt with
  Mali's default `RGBA_8888` config.
- `JAWT_GetAWT` thread-safety (still flagged in scope doc risk #3).

## Next leg recommendation

Build a small Android Activity + bound Service in `com.runelitetablet`:

1. Activity hosts a `SurfaceView` and binds a remote service via AIDL.
2. AIDL ships `surface: android.view.Surface` to the service process.
3. Service calls `ANativeWindow_fromSurface(env, surface)` in JNI, creates
   an EGL window surface from it, renders a triangle, swap-buffers.
4. Activity drives 100 SurfaceView resize cycles via `LayoutParams` and
   verifies the service's renders follow without orphaning buffers.

Estimated effort: 1–1.5 days. If this passes too, the full 5a + 5b-A
engineering block (4–6 days) is justified.

## Bottom line

The Mali driver is not the blocker. The remaining risk lives in the
Java-side Surface marshaling layer, which is the next test, not this one.

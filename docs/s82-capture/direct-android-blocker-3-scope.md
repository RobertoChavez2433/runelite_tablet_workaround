# Direct-Android-Surface Blocker #3 — Scoping Report

**Date**: 2026-04-26 (S82)
**Question**: Can we replace `librlawt.so` with one that draws directly to an Android SurfaceView via Android EGL, bypassing virgl/Mesa/X11? RuneLite Java cannot be modified.
**Verdict**: **Feasible but risky. 7-10 days. Run a 1-2 day de-risker first.**

## What rlawt actually does today

JNI surface (`third_party/rlawt/AWTContext.java:101-180`):

| Method | Purpose |
|---|---|
| `create0(Component)` | Lock JAWT, hold a `JAWT_DrawingSurface` from the Canvas |
| `createGLContext()` | Extract X Window ID via `JAWT_X11DrawingSurfaceInfo` (`rlawt_nix.c:172-176`), open X Display, create GLXContext |
| `makeCurrent()` / `detachCurrent()` | `glXMakeCurrent` |
| `swapBuffers()` | `glXSwapBuffers` (or `glFinish` if single-buffered) |
| `setSwapInterval(int)` | EXT/SGI swap control |

State (`rlawt.h:131-169`): `Display* dpy`, `Drawable drawable`, `GLXContext context`. After `createGLContext`, the Canvas is no longer touched — only the X Window ID matters.

Termux:X11 already proves the Android path works (`LorieView.kt:25-287` → `renderer.c:647-702`): SurfaceHolder callback → JNI → `ANativeWindow_fromSurface()` → `eglCreatePlatformWindowSurface()`. Sustains 120 Hz with no copy. But the X server runs **in-process** with the activity, so no cross-process surface handoff was needed.

## The two sub-problems

### 5a — Cross-process surface handoff (JVM ↔ Android app)

rlawt runs in the JVM under Termux's UID. SurfaceView lives in `com.runelitetablet`. Different processes. `ANativeWindow*` is process-local but its underlying buffer-queue FDs are shareable.

**Solution**: Binder ParcelFileDescriptor → marshal `{fd, w, h, format, usage}` → JVM side rebuilds `ANativeWindow*`.

**Feasibility: YES.** Effort: 2-3 days. Top risks:
- FD ownership: app closes Surface while JVM still holds the ANativeWindow
- Format mismatch: LorieView forces BGRA_8888 (`LorieView.kt:50`), rlawt may default to RGBA on Mali
- Deadlock: app render thread + JVM render thread both lock EGL contexts

### 5b — AWT canvas chrome (resize/lifecycle vs. ANativeWindow)

X11 path is layout-agnostic — the X server handles resize/move via ConfigureNotify and the GL context never re-attaches. ANativeWindow has no such free ride; rlawt must respond to Canvas component events.

**Two approaches:**

| Approach | Effort | Risk |
|---|---|---|
| **5b-A:** Add ComponentListener in `AWTContext.java`, new JNI `notifyResized(w, h)` to recreate EGL surface. Doesn't change RL Java, only OUR rlawt jar. | 1-2 days | Low |
| **5b-B:** Fake X drawable + GLX shim that intercepts `glXMakeCurrent` and dispatches to EGL behind the scenes. Preserves rlawt's existing API verbatim. | 3-4 days | High — interposition fragile to Mesa/NDK upgrades |

**Feasibility: PARTIAL.** 5b-A is the pragmatic choice — `AWTContext.java` is OUR Java in `third_party/rlawt/`, not RuneLite's, so adding a listener doesn't touch any trust-sensitive code.

## Recommended de-risker (cheap throwaway)

Before committing 7-10 days, run a 1-2 day / ~200 LoC standalone C test:
1. App-side: send the SurfaceView's underlying buffer-queue FD to a peer process via Unix socket (Binder analogue).
2. Peer-side: receive FD, rebuild `ANativeWindow*`, create EGL surface, render a colored triangle.
3. Exercise: resize via SurfaceHolder, verify the peer's EGL surface follows.

**Pass criterion**: Triangle renders, lifecycle clean, no FD leaks across 100 resize cycles.
**If it fails**: Cross-process ANativeWindow isn't viable on this Mali driver; we fall back to optimizing the existing virgl path (different effort tree).
**If it passes**: Commit to 5a + 5b-A as the next major work block.

## Top 3 risks across both sub-problems

1. **Binder FD cleanup race** — app closes Surface, JVM-side EGL surface orphans silently. Mitigation: explicit lifecycle protocol (app sends "surface-going-away" before close; JVM acks).
2. **BGRA/RGBA format mismatch** — LorieView pins BGRA_8888, Mali EGL configs default to RGBA. Mitigation: probe + log the chosen EGLConfig color order on JVM side; reject mismatches early.
3. **`JAWT_GetAWT` thread safety** — JAWT lock expects AWT EDT; rlawt's platform init may run elsewhere under our launcher. Termux:X11 doesn't hit this (single process); we will. Mitigation: ensure `create0` runs on EDT.

## Bottom line

Direct-Android-surface is realistic. Not this week, but realistic. The 5a+5b-A combo is the right shape — minimum 4-6 days of focused work plus 1-2 days for the de-risker. **The correct next step is the de-risker, not the full refactor.** It costs almost nothing relative to the engineering bet, and tells us yes/no before we invest a week.

Until then, the existing virgl path with the new openbox + JIT pre-warm flags is what users get.

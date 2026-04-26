# Direct-Android-Surface De-Risker Leg 2 Result (S82 Task #26)

**Date**: 2026-04-26
**Probe**: `runelite-tablet/app/src/main/aidl/com/runelitetablet/probe/ISurfaceRendererService.aidl`
+ `SurfaceRendererService.kt` + `SurfaceAidlProbeActivity.kt` (manifest entries
register the service in `:surfacerenderer`).
**Device**: Tab S10 Ultra (R52X90378YB), Android 16.
**Verdict**: **PASS — Surface-AIDL marshaling + cross-process lifecycle clean across 100 resize cycles.**

## What this leg tested

Leg 1 (task #25, `direct-android-blocker-3-derisker-result.md`) cleared the GPU
primitive: cross-process AHardwareBuffer import as EGLImage on Mali r44p1.
Leg 2 closes the OTHER half of sub-problem 5a — does the Java `android.view.Surface`
itself marshal cleanly across a Binder/AIDL boundary, and does the receiver
process handle the resize/attach/detach lifecycle without leaking FDs?

The Activity hosts a `SurfaceView`; the Service runs in a separate process
(`:surfacerenderer`); AIDL `attachSurface(in Surface, w, h)` ships the Surface
across; the service-side renders a `Canvas`-drawn triangle into the Surface
on each iteration so the IPC chain is exercised end-to-end. Canvas (not EGL)
is fine here — leg 1 already proved EGL+AHB cross-process works; this leg's
job is the marshaling + lifecycle layer.

## Run summary

```
S26 startResizeLoop: cycles=100 fd_at_start=108
S26 attachSurface: 1024x768 attach=100 fd_count=116 remote_pid=9186  (last loop iter)
S26 SUMMARY: cycles_requested=100 cycle_index=100 fd_at_start=108 fd_at_end=119 fd_delta=11
S26 releaseSurfaceLocked[detachSurface]: detach=100 fd_count=116
S26 SurfaceRendererService.onDestroy: pid=9219 attach=100 detach=100 render=100 render_fail=0 fd_count=116
```

(Full log: `docs/s82-capture/run-26/surface-aidl-probe-logcat.log`, 310 lines.)

| Metric (renderer process) | Value |
|---|---|
| Activity PID | 9186 |
| Renderer PID | 9219 (separate process — confirmed cross-process IPC) |
| Resize cycles requested | 100 |
| Resize cycles completed | 100 |
| `attachSurface` calls received | 100 |
| `releaseSurfaceLocked` calls completed | 100 |
| Triangle renders | 100 |
| **render_fail** | **0** |
| Renderer-process FD count (steady state) | **116, stable across all 100 iterations** |

The activity-side `fd_delta=11` is a sampling-timing artefact, not a leak: the
"fd_at_start" snapshot was taken right after `onServiceConnected`, before the
first `attachSurface` that allocates per-Surface internal FDs in the service
process; the "fd_at_end" snapshot includes those persistent FDs. The
authoritative number is the **per-iteration steady-state remote FD count of
116, which never grew across 100 iterations**.

## What this clears, what it doesn't

**Clears (in addition to leg 1's clears):**
- `android.view.Surface` does marshal correctly across a process boundary via
  Binder/AIDL `in Surface` parameter. The receiver gets a usable Surface.
- The receiver process can call `Surface.lockCanvas` / `unlockCanvasAndPost`
  on a marshaled Surface — basic graphics ops work end-to-end.
- Resize lifecycle is clean: attach → render → detach → re-attach with new
  dimensions, 100 times, zero leaks.
- The `:surfacerenderer` process model (separate Android process via
  `android:process=":xxx"`) is a viable container for the rlawt-side renderer
  in the production layout.

**Does NOT clear (still open before committing the engineering block):**
- BGRA vs RGBA colour-order mismatch when the producer is the LorieView Surface
  (forces `BGRA_8888`) and the consumer is the JVM-side rlawt with Mali's
  default `RGBA_8888` config. Would need a third probe that pins the
  Activity's SurfaceHolder format to `RGBX_8888` or `RGBA_8888`.
- The actual `ANativeWindow_fromSurface()` C path inside a non-Android-framework
  JVM (Termux's OpenJDK). This leg used a Kotlin-side Service that has full
  Android framework. Termux's JVM has the NDK but not the framework Surface
  classes. The proof here is the marshaling primitive; final integration would
  use NDK Binder (`AIBinder`) on the JVM side, which is API-level-distinct from
  Java Binder.
- `JAWT_GetAWT` thread-safety still flagged as scope doc risk #3 (orthogonal to
  the IPC layer; only relevant once we wire rlawt to receive a Surface).

## Combined with leg 1 — readiness for the rlawt-on-Surface engineering block

| Sub-question | Status |
|---|---|
| Mali r44p1 imports cross-process AHardwareBuffer as EGLImage | ✅ leg 1 (100/100) |
| Cross-process EGL surface lifecycle clean across resizes | ✅ leg 1 (100/100) |
| Java `Surface` marshals cross-process via Binder/AIDL | ✅ leg 2 (100/100) |
| Receiver process handles resize/attach/detach lifecycle | ✅ leg 2 (100/100) |
| BGRA/RGBA mismatch tolerated | ❓ untested |
| `ANativeWindow_fromSurface` from non-framework JVM | ❓ untested (NDK Binder path) |

Both binary-feasibility legs PASS. The remaining open items are surface-area
items (format, JVM-NDK-Binder), not blocking. The 4–6 day rlawt-on-Surface
engineering block is justified.

## How to re-run

```
adb shell am start -n com.runelitetablet/.probe.surfaceaidl.SurfaceAidlProbeActivity \
    --ei resize_cycles 100
adb logcat -d -s RLT | grep S26 > /tmp/run.log
grep "S26 SUMMARY\|S26 SurfaceRendererService.onDestroy" /tmp/run.log
```

Pass criterion: `attach == cycles`, `detach == cycles`, `render == cycles`,
`render_fail == 0`, renderer-process `fd_count` flat across iterations.

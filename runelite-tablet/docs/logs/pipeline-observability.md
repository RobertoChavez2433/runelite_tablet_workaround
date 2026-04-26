# Pipeline Observability Reference

Written during the S81 logging audit. Single source of truth for "what do I grep / read to see a given layer" when diagnosing FPS on the native-Termux path.

## Pipeline flow

```
RuneLite Client (JVM in Termux)
    → RL GpuPlugin (LWJGL)
        → libGL.so  (Mesa virpipe front-end)
            → unix socket → virgl_test_server_android
                → Mali driver → Mali GPU
            ← back through virgl / Mesa
    → rlawt glXSwapBuffers
        → X11 protocol (Unix socket) → com.termux.x11 X server (lorie)
            → lorie damage → AChoreographer gate → GLES renderer
                → eglSwapBuffers → Android Surface (LorieView.SurfaceView)
                    → SurfaceFlinger → Display (120 Hz panel)
```

## Log source reference

### Producer side (inside Termux / JVM)

| Hop | Tag / file | File:line | Cadence | What to read |
|---|---|---|---|---|
| RL Client log | `client.log` | RL repo | continuous | `Using driver:` line for GL init only; no stable per-frame FPS |
| rlawt in-process probes | `[rlawt-perf]` / `[rlawt-info]` in `runelite-native.log` | `third_party/rlawt/rlawt_nix.c:677` | per-window (default 60 frames) | `swap_us mean/p50/p95/max`, `gap_us mean/p50/p95/max`, optional `finish_us / xsync_us / gpu_us`, `mkcur`, `glerr` |
| **rlawt per-frame CSV** (S81) | `$HOME/rlawt-perframe.csv` | `rlawt_nix.c:636` | per-frame | `frame,t_post_swap_ns,swap_us,gap_us,finish_us,xsync_us,gpu_us,mkcur_cumulative` |
| VirGL server init | `virgl-server.log` (tail in DIAG) | Termux pkg binary | init only | no per-frame telemetry |
| Mali GPU busy | `runelite-native-perf.log` `GPU_BUSY` lines | `perf-sampler.sh:131` | 1/s | sysfs scalar 0-100 |
| Launcher CPUSET/DIAG/AFFINITY/RL-CONFIG | `runelite-native.log` | `launch-runelite-native.sh` | once at launch | entry cpuset pid, DIAG preflight block, taskset result, profile patches |
| **CPUSET SETTLE (T=30s)** (S81) | `runelite-native.log` block `=== CPUSET SETTLE ===` | `launch-runelite-native.sh:~625` | once at T+30s | JVM + virgl + Client thread + AWT-EventQueue effective cpuset / Cpus_allowed_list |
| Per-thread sampler | `runelite-native-perf.log` `TICK` lines | `perf-sampler.sh:76` | 1/s | pid/tid/comm/state/cpus/vol/nonvol/schedstat |

### Consumer side (X server → Android)

| Hop | Tag / file | File:line | Cadence | What to read |
|---|---|---|---|---|
| Lorie X server counters | logcat `LorieNative:I` | `InitOutput.c:513` | 5s | `choreographer callbacks`, `redraw wakeups`, `damage-triggered`, `present flip attempts`, `rendered frames FPS`, `AhbLockTrace` |
| Lorie GLES renderer | logcat `gles-renderer:D` `XloriePerf:` | `renderer.c:247` | 1s | `frames`, `avg_lock/fence/swap/frame/inter_frame`, `max_*`, `estimated_fps`, `content_util`, `starved`, `vsyncs`, `wait_choreo/content/state` |
| **Durable Xlorie log** (S81) | `$HOME/runelite-native-perf-xlorie.log` | `perf-sampler.sh` (logcat side-car) | continuous | mirror of above two so logcat buffer rolls don't lose data |
| LorieView surface lifecycle | logcat `RLT` tag, `[SURFACE]` / `[WINDOW]` | `LorieView.kt:47` / `HybridX11HostActivity.kt:191` | lifecycle events | surfaceCreated/Changed/Destroyed with W×H, format, `refresh=NHz` |
| **Display mode dump** (S81) | logcat `RLT [DISPLAY]` | `HybridX11HostActivity.kt:167` + `LorieView.kt:61` | onCreate + surfaceCreated | `activeMode`, `supportedModes`, `supportedRefreshRates`, `displayId`, `frameRateSetOnSurface` |
| RLT Choreographer | logcat `RLT [FRAME]` | `HybridX11HostActivity.kt:76` | 120 frames | `fps=`, `jank=`, `p99=`, `heap=` |
| RLT jank events | logcat `RLT [JANK]` | `HybridX11HostActivity.kt:87` | per-frame over 12ms threshold | actual ms, frame id |
| RLT PerfDashboard | logcat `RLT [PERF] PERF_SUMMARY` | `PerfDashboard.parseXloriePerfLine` | per XloriePerf | `renderer_fps`, `wait_*_pct` |

### Host-side companion (S81)

| Source | File | Cadence | Why host-side |
|---|---|---|---|
| `dumpsys SurfaceFlinger --list` | `docs/s81-capture/sf-list.log` | 2s | Termux UID lacks DUMP permission |
| `dumpsys SurfaceFlinger --latency <layer>` | `docs/s81-capture/sf-latency.log` | 2s | same |
| `dumpsys gfxinfo <pkg> framestats` | `docs/s81-capture/gfxinfo.log` | ~10s | same |

Start with `scripts/capture-android-frame-stats.sh <serial> <outdir> [interval_sec]`.

## How to read a steady-state run

1. Look at `[DISPLAY]` in logcat → confirm `activeMode @ 120.0 Hz` and note `supportedModes`.
2. Look at `LorieNative` line `choreographer callbacks = X FPS` → if `< 120`, Android is throttling the surface (DRR under low-content, or `setFrameRate` not called).
3. Look at `LorieNative: damage-triggered redraws` → this is the X11 content update rate = Producer ceiling.
4. Look at rlawt `[rlawt-perf]` `gap_us + swap_us` → producer frame cadence inside the JVM.
5. Look at rlawt per-frame CSV `t_post_swap_ns` deltas → true per-frame rlawt cadence.
6. Look at `CPUSET SETTLE` → JVM + virgl + Client thread cpuset / Cpus_allowed_list.
7. Look at `RLT [FRAME]` → Android Choreographer rate on the RLT UI thread (should always be 120 on 120Hz panel).
8. Host `sf-latency` → compositor-side present timestamps for the LorieView SurfaceView layer.

Layer-to-layer divergences are the diagnostic signal:

- `[DISPLAY] 120` but `LorieNative choreographer = 30` → SurfaceFlinger throttling → missing `setFrameRate` on the LorieView surface, or low-content DRR.
- `LorieNative choreographer = 120` but `damage = 27` → bottleneck is upstream of X11 (RL + rlawt + virgl producing content at 27 FPS).
- CSV inter-frame delta ≫ `swap_us + gap_us` window mean → mixing active-and-idle frames in the window mean; trust CSV.
- `CPUSET SETTLE` cpuset=/moderate → TermuxProcessPin's BIND_IMPORTANT hoist failed to persist → JVM + virgl clamped to cpus 0-3.

## S81 audit baseline (Varrock East Bank, probes-OFF, GPU plugin on)

```
Display                        120 Hz (active mode id=2:1848x2960@120)
RLT Choreographer              120 FPS jank=0 p99=8.3ms
Xlorie redraw wakeups          120 FPS (active), 30 FPS (idle/paused)
Xlorie damage-triggered         26.4 FPS
Xlorie rendered frames          26.4 FPS
rlawt inter-post-swap delta    ~37 ms  (~27 FPS)
RL FpsPlugin overlay            28 FPS
CPUSET @ T+30s                  JVM + VIRGL + Client all /moderate cpus=0-3
```

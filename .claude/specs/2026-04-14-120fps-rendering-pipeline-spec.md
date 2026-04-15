# 120 FPS Rendering Pipeline Spec

**Date**: 2026-04-14 | **Status**: Draft | **Branch**: `spike/direct-android-surface`
**Device**: Samsung Galaxy Tab S10 Ultra (Dimensity 9300+, Immortalis-G720 MC12, 120 Hz)
**Baseline**: 36-54 FPS (mean ~43) | **Target**: 120 FPS sustained

## Problem Statement

RuneLite gameplay renders at 36-54 FPS on a 120 Hz display. The Kotlin/Compose UI achieves 120 FPS with 0 jank, confirming the display hardware is not the bottleneck. The bottleneck is content production: RuneLite produces only 34-36 damage events/sec through the VirGL pipeline.

Current per-frame time is ~24ms. Target is 8.33ms (120 FPS). This requires a 3x reduction.

### Root Causes (from fps-bottleneck-research.md)

| # | Cause | Impact | Evidence |
|---|-------|--------|----------|
| 1 | CPU core scheduling — RuneLite + VirGL on little cores (2.0 GHz), big/prime cores idle | ~40-70% throughput loss | `/proc/PID/stat` field 39 = CPU 0,1 |
| 2 | ptrace overhead — proot intercepts every syscall via ptrace | ~50% throughput loss | stime/utime ratio 1.17 (54% kernel time) |
| 3 | VirGL pipeline serialization — synchronous IPC per GL call + triple translation | ~3-4ms latency/frame | avg_inter_frame_ms 22-27ms |

### Research Conclusions (VirGL bypass candidates)

| Candidate | Verdict | Reason |
|-----------|---------|--------|
| llvmpipe | Dead end | 5-15 FPS for GPU plugin workload (worse than VirGL) |
| Zink direct in proot | Blocked | glibc/Bionic ABI wall prevents Mali Vulkan ICD loading |
| PanVK + Panthor | Blocked | Requires mainline Linux 6.10+ kernel; Samsung ships kbase 5.10/5.15 |
| Server-side Zink | Possible | Swap ANGLE for Zink on Termux-native virgl_test_server; +63% on Mali-G76 |

### Related Specs

- `2026-03-16-presentation-pipeline-120fps-spec.md` — Presentation pipeline fixes (legacy drawing, waitForNextFrame, AHardwareBuffer zero-copy)
- `2026-03-09-virgl-test-pipeline-spec.md` — GL test harness and LD_PRELOAD shims

## Hard Constraints

- 200 LoC max per class/method; 50 LoC max for bootstraps/factories
- No mocking frameworks (Mockito/MockK). Test real components. Only fake Android-boundary deps.
- No device rooting
- No modifying RuneLite source (third-party Java app)
- Must stay on proot (no `/dev/dri` kernel driver access on stock Samsung)
- Mali/Immortalis GPU (no Turnip/Zink from proot)
- All Xlorie native code is vendored and buildable in-app

## Verification Gate

**120 FPS sustained** on a logged-in RuneLite session, measured by:
- `damage-triggered redraws` >= 120 FPS (LorieNative tag, ADB logcat)
- `estimated_fps` >= 120 FPS (gles-renderer tag, debug server)
- `content_util` = 100% (no starvation)
- `avg_inter_frame_ms` <= 8.33ms
- Sustained for >= 60 seconds during gameplay

If 120 FPS is not achievable within Part 1, the gate for Part 1 is:
- **Best achievable FPS** with all Part 1 optimizations applied
- **Documented ceiling** with data showing why further gains require Part 2

---

## Part 1: Optimize Within VirGL (Target: 80-100 FPS)

### Phase 0: System-Wide Observability

**Priority**: ABSOLUTE FIRST. No optimization work begins until observability is complete.
**Rationale**: Current instrumentation covers the renderer thread well but has blind spots in the content production pipeline, CPU scheduling, and ptrace overhead. Every optimization in Phase 1-4 requires before/after metrics from these new instrumentation points.

---

#### Task 0A: Renderer Wait-Reason Tracking

**What**: Break down renderer idle time by wait reason. Currently we know the renderer sleeps in `pthread_cond_wait` but not WHY (choreographer gate vs no content vs state change).

**Where**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`

**Implementation**:

Add wait-reason counters to `rendererPerfStats` (line ~149):
```c
uint64_t waitChoreographerNs;  // time blocked by waitForNextFrame
uint64_t waitContentNs;         // time blocked waiting for drawRequested
uint64_t waitStateNs;           // time blocked on state/window/buffer changes
```

In `rendererThread()` (line ~796), before `pthread_cond_wait`, snapshot the clock. After wake, classify the reason based on which condition in `rendererShouldWait()` was false, and accumulate into the appropriate counter.

Add to the existing `XloriePerf` log line (line ~230):
```
wait_choreo=%.1fms wait_content=%.1fms wait_state=%.1fms
```

**Metrics exposed**: `wait_choreo_ms`, `wait_content_ms`, `wait_state_ms` per reporting window.

**Test**: `RendererWaitReasonTest` — Unit test in C (compile with host gcc, not Android NDK) that exercises the wait-reason classification logic in isolation. Extract the classification into a pure function `classifyWaitReason(bool waitForNextFrame, bool drawRequested, bool stateChanged, bool windowChanged, bool buffersChanged)` returning an enum. Test all combinations.

**Files**:
- `renderer.c` — Add counters, classification function, log output
- `runelite-tablet/app/src/test/cpp/RendererWaitReasonTest.c` — Host-compiled C test

**LoC estimate**: ~40 lines in renderer.c, ~60 lines test

---

#### Task 0B: CPU Core Affinity Monitor

**What**: Continuously monitor which CPU core RuneLite, VirGL, and proot are scheduled on. Log core migrations and time-on-core distribution.

**Where**: New shell script `monitor-cpu-affinity.sh` deployed as an asset, invoked from `launch-runelite.sh`.

**Implementation**:

Shell script that reads `/proc/<PID>/stat` field 39 (processor) every 2 seconds for each target PID. Outputs structured log lines:
```
AFFINITY: pid=6258 name=RuneLite cpu=0 type=little freq=2000000
AFFINITY: pid=26636 name=VirGL cpu=1 type=little freq=2000000
```

Core type classification uses `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`:
- <= 2100000: little
- <= 2900000: big
- > 2900000: prime

**Kotlin integration**: New `CpuAffinityMonitor` class (< 100 LoC) that:
1. Accepts a `Logger` and list of PIDs
2. Parses `/proc/PID/stat` field 39
3. Logs via `Logger.perf()` with AFFINITY tag
4. Tracks core migration count per PID

**Test**: `CpuAffinityMonitorTest` — Feed synthetic `/proc/PID/stat` lines to the parser. Verify core type classification, migration detection, and log output via `PrintLogger`.

**Files**:
- `runelite-tablet/app/src/main/assets/scripts/monitor-cpu-affinity.sh` — Shell monitor
- `runelite-tablet/app/src/main/java/com/runelitetablet/perf/CpuAffinityMonitor.kt` — Kotlin wrapper
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/CpuAffinityMonitorTest.kt` — Unit test

**LoC estimate**: ~50 lines shell, ~80 lines Kotlin, ~80 lines test

---

#### Task 0C: Pipeline Benchmark Harness

**What**: Automated script that runs `glxgears` through the full proot+VirGL pipeline and reports FPS. Establishes the pipeline ceiling independent of RuneLite.

**Where**: New shell script `benchmark-pipeline.sh` deployed as an asset.

**Implementation**:

```bash
#!/data/data/com.termux/files/usr/bin/bash
# Run inside proot via Termux
DISPLAY=:0 timeout 30 glxgears 2>&1 | tee /tmp/glxgears.log
# Parse: "300 frames in 5.0 seconds = 60.0 FPS"
grep "frames in" /tmp/glxgears.log | tail -5
```

Also runs `glxinfo` to capture GL_RENDERER, GL_VERSION, and extension list for baseline documentation.

**Kotlin integration**: `PipelineBenchmark` class (< 80 LoC) that:
1. Executes the script via `TermuxCommandRunner`
2. Parses FPS from stdout
3. Logs result via `Logger.perf()` with BENCHMARK tag
4. Returns structured `BenchmarkResult(fps: Double, glRenderer: String, glVersion: String)`

**Test**: `PipelineBenchmarkTest` — Feed sample glxgears output to the parser. Verify FPS extraction, error handling for missing glxgears, and timeout behavior.

**Files**:
- `runelite-tablet/app/src/main/assets/scripts/benchmark-pipeline.sh` — Shell harness
- `runelite-tablet/app/src/main/java/com/runelitetablet/perf/PipelineBenchmark.kt` — Kotlin wrapper
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/PipelineBenchmarkTest.kt` — Unit test

**LoC estimate**: ~30 lines shell, ~70 lines Kotlin, ~60 lines test

---

#### Task 0D: VirGL Environment Profiler

**What**: Script that captures all VirGL/Mesa/GALLIUM environment variables, VirGL server process state, and socket health. Provides a snapshot of the pipeline configuration for diagnostics.

**Where**: New shell script `profile-virgl-env.sh` deployed as an asset.

**Implementation**:

Captures:
1. All env vars: `GALLIUM_DRIVER`, `MESA_GL_VERSION_OVERRIDE`, `MESA_NO_ERROR`, `MESA_EXTENSION_OVERRIDE`, `VIRGL_DEBUG`, `VTEST_SOCKET_NAME`
2. VirGL server process state: PID, CPU%, memory, thread count, uptime
3. Socket health: `ls -la /tmp/.virgl_test` or equivalent socket path
4. ANGLE version: `adb shell dumpsys gfxinfo` or ANGLE env vars
5. GPU clock state: `/sys/class/devfreq/*/cur_freq` if accessible

Output is structured key=value pairs logged to stdout with `VIRGL_ENV:` prefix.

**Kotlin integration**: `VirglEnvironmentProfiler` class (< 80 LoC) that parses the output and logs via `Logger.perf()`.

**Test**: `VirglEnvironmentProfilerTest` — Feed sample `profile-virgl-env.sh` output to the parser. Verify all fields are extracted.

**Files**:
- `runelite-tablet/app/src/main/assets/scripts/profile-virgl-env.sh` — Shell profiler
- `runelite-tablet/app/src/main/java/com/runelitetablet/perf/VirglEnvironmentProfiler.kt` — Kotlin wrapper
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/VirglEnvironmentProfilerTest.kt` — Unit test

**LoC estimate**: ~60 lines shell, ~70 lines Kotlin, ~50 lines test

---

#### Task 0E: ptrace Overhead Estimator

**What**: Measure the kernel time ratio (`stime/utime`) for all pipeline processes, which quantifies ptrace overhead. Normal apps have 5-15% kernel time; proot-wrapped processes show >50%.

**Where**: New Kotlin class reading `/proc/PID/stat`.

**Implementation**:

`PtraceOverheadEstimator` class (< 100 LoC):
1. Reads `/proc/PID/stat` for each target PID
2. Extracts fields 14 (`utime`) and 15 (`stime`)
3. Computes `kernel_ratio = stime / (utime + stime)`
4. Logs: `PTRACE: pid=6258 name=RuneLite utime=49294 stime=57670 kernel_ratio=0.54`
5. Flags processes with `kernel_ratio > 0.30` as ptrace-impacted

Samples every 10 seconds. Tracks delta between samples (not cumulative) for accurate per-interval measurement.

**Test**: `PtraceOverheadEstimatorTest` — Feed synthetic `/proc/PID/stat` content. Verify utime/stime extraction, ratio calculation, delta tracking, and threshold flagging.

**Files**:
- `runelite-tablet/app/src/main/java/com/runelitetablet/perf/PtraceOverheadEstimator.kt`
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/PtraceOverheadEstimatorTest.kt`

**LoC estimate**: ~80 lines Kotlin, ~80 lines test

---

#### Task 0F: X Server Log Bridge

**What**: Surface X server logs (LorieNative tag, currently ADB-only) to the WebSocket debug server for real-time monitoring without a connected dev machine.

**Where**: `DebugLogServer.kt` already has a `logcatBridge()` that reads logcat and forwards to WebSocket clients. It filters for `LorieNative:V`, `gles-renderer:V`, `XlorieCaps:V`.

**Current gap**: The logcat bridge can only read logs from the app's own process group. The X server (PID 5533) logs are in a separate process and Android restricts cross-process logcat access.

**Implementation**:

Option 1 (preferred): The X server already runs within our app's process group (internal-hybrid mode). Verify that the existing `logcatBridge` captures X server logs. If not, expand the logcat filter to include the X server's PID.

Option 2 (fallback): Add a file-based log sink to `InitOutput.c`. Write structured log lines to `/data/data/com.runelitetablet/files/x11-server.log`. Add a `FileLogBridge` to `DebugLogServer` that tails this file and forwards entries.

**Test**: `XServerLogBridgeTest` — For Option 1: verify logcat filter captures expected tags. For Option 2: write sample log lines to a temp file, verify `FileLogBridge` reads and broadcasts them correctly.

**Files**:
- `DebugLogServer.kt` — Verify/expand logcat bridge (Option 1) or add FileLogBridge (Option 2)
- `InitOutput.c` — Add file-based log sink (Option 2 only)
- `runelite-tablet/app/src/test/java/com/runelitetablet/logging/XServerLogBridgeTest.kt`

**LoC estimate**: ~40 lines Kotlin, ~50 lines test (Option 1); ~80 lines C + 60 lines Kotlin + 60 lines test (Option 2)

---

#### Task 0G: Perf Dashboard Aggregator

**What**: Kotlin class that collects metrics from all Phase 0 components and emits a periodic aggregate summary every 10 seconds via the debug server.

**Where**: New class in `perf/` package.

**Implementation**:

`PerfDashboard` class (< 120 LoC):
1. Accepts references to: `CpuAffinityMonitor`, `PtraceOverheadEstimator`, `Logger`
2. Every 10 seconds, collects latest metrics and emits a single `PERF_SUMMARY` log line:
```
PERF_SUMMARY: rl_cpu=0(little) virgl_cpu=1(little) rl_kernel_ratio=0.54 virgl_kernel_ratio=0.58 renderer_fps=42 wait_choreo_pct=25 wait_content_pct=60 wait_state_pct=15
```
3. Parses native renderer stats from the existing `XloriePerf` logcat lines captured by the log bridge

**Test**: `PerfDashboardTest` — Wire up `PrintLogger`, fake metric sources, verify summary format and emission interval.

**Files**:
- `runelite-tablet/app/src/main/java/com/runelitetablet/perf/PerfDashboard.kt`
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/PerfDashboardTest.kt`

**LoC estimate**: ~100 lines Kotlin, ~80 lines test

---

#### Phase 0 Verification

All Phase 0 tasks are complete when:
- [ ] `./gradlew test` passes with all new tests green
- [ ] Debug server at port 8099 streams AFFINITY, PTRACE, VIRGL_ENV, BENCHMARK, PERF_SUMMARY events
- [ ] Native renderer logs include `wait_choreo`, `wait_content`, `wait_state` breakdown
- [ ] X server logs visible in debug server (LorieNative tag)
- [ ] glxgears benchmark runs and reports FPS through the pipeline

---

### Phase 1: CPU Core Affinity

**Depends on**: Phase 0 (need CpuAffinityMonitor to verify the change)
**Expected gain**: ~50-60 FPS (from ~42 FPS) — 42-70% faster cores
**Risk**: Low — `taskset` is available in Termux; if it fails, processes stay on current cores

---

#### Task 1A: Pin RuneLite + VirGL to Big/Prime Cores

**What**: Add `taskset` calls to `launch-runelite.sh` to pin RuneLite's Java process and virgl_test_server to CPU 4-7 (big + prime cores).

**Where**: `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`

**Implementation**:

After RuneLite and VirGL server PIDs are known, add:
```bash
# Pin to big+prime cores (CPU 4-7): bitmask 0xF0
log_info "AFFINITY: Pinning RuneLite (PID=$RUNELITE_PID) to big/prime cores"
taskset -p 0xF0 "$RUNELITE_PID" 2>/dev/null && \
    log_info "AFFINITY: RuneLite pinned successfully" || \
    log_warn "AFFINITY: taskset failed for RuneLite, staying on default cores"

VIRGL_PID=$(pidof virgl_test_server_android 2>/dev/null)
if [ -n "$VIRGL_PID" ]; then
    taskset -p 0xF0 "$VIRGL_PID" 2>/dev/null && \
        log_info "AFFINITY: VirGL (PID=$VIRGL_PID) pinned successfully" || \
        log_warn "AFFINITY: taskset failed for VirGL"
fi
```

Also pin proot to big cores (it's single-threaded, benefits from faster core):
```bash
PROOT_PID=$(pidof proot 2>/dev/null)
if [ -n "$PROOT_PID" ]; then
    taskset -p 0x80 "$PROOT_PID" 2>/dev/null  # CPU 7 (prime, 3.4 GHz)
fi
```

**Verification**: `CpuAffinityMonitor` (from Task 0B) should show:
- RuneLite CPU field = 4-7
- VirGL CPU field = 4-7
- proot CPU field = 7

**Test**: `CpuAffinityScriptTest` — Shell test that validates:
1. `taskset` command is available
2. Script handles missing PIDs gracefully
3. Script handles `taskset` failure gracefully
4. Log output contains expected AFFINITY lines

**Files**:
- `launch-runelite.sh` — Add taskset calls
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/CpuAffinityScriptTest.kt` — Integration test

**LoC estimate**: ~20 lines shell, ~40 lines test

---

#### Task 1B: Validate Affinity Persistence

**What**: Verify that `taskset` pinning persists across the entire session. Android's EAS scheduler may re-migrate processes. Add a periodic check (every 30 seconds) that re-pins if migration detected.

**Where**: `monitor-cpu-affinity.sh` (from Task 0B)

**Implementation**:

Extend the monitoring script to:
1. Check current core assignment
2. If RuneLite/VirGL migrated back to little cores (CPU 0-3), re-apply `taskset`
3. Log migration events: `AFFINITY_MIGRATION: pid=6258 from=4(big) to=0(little) re-pinned=true`

**Test**: Already covered by `CpuAffinityMonitorTest` — add test case for migration detection and re-pin logic.

**LoC estimate**: ~15 lines shell, ~20 lines test additions

---

#### Phase 1 Verification

- [ ] `CpuAffinityMonitor` shows all three processes on big/prime cores
- [ ] No migration back to little cores within 60-second observation window
- [ ] FPS improvement measured via `PerfDashboard` (expect ~50-60 FPS from ~42)
- [ ] Startup time not regressed (taskset adds < 100ms)

---

### Phase 2: Presentation Pipeline Fixes

**Depends on**: Phase 0 + Phase 1
**Expected gain**: +10-30 FPS (from presentation-pipeline spec)
**Risk**: Low-Medium

This phase implements the fixes from the existing `2026-03-16-presentation-pipeline-120fps-spec.md`. Refer to that spec for full implementation details. Summary of tasks:

---

#### Task 2A: Fix Legacy Drawing Fallback

**From**: Existing spec Phase 1

Fix `rendererTestCapabilities()` to use `R8G8B8A8_UNORM` (value 1) instead of `R8G8B8X8_UNORM` (value 2) for the RGBA retry path on Mali. This eliminates the 5-15ms per-frame `glTexSubImage2D` upload.

**Test**: Add diagnostic logging to `rendererTestCapabilities()` that reports which step fails. Verify via debug server that `legacy_drawing` is no longer set.

**Files**: `renderer.c`

---

#### Task 2B: Remove waitForNextFrame Cap

**From**: Existing spec Phase 2

Remove `state->waitForNextFrame = true` from `rendererRedrawLocked()` and the post-swap fence wait block. This saves ~10ms of dead wait per frame.

**Test**: Verify `avg_inter_frame_ms` drops after change. Verify no visual jank via 60-second gameplay observation.

**Files**: `renderer.c`, `InitOutput.c`

---

#### Task 2C: Hybrid Host Lifecycle Parity

**From**: Existing spec Phase 4

Add missing upstream `reloadPreferences()` and `clientConnectedStateChanged()` calls to `HybridX11HostActivity`.

**Test**: `HybridX11HostLifecycleTest` — Verify preference reload is called after X connection. Verify no `legacy_drawing` regression.

**Files**: `HybridX11HostActivity.kt`, `LorieView.kt`

---

#### Phase 2 Verification

- [ ] No `"Forcing legacy drawing"` in logs
- [ ] `avg_inter_frame_ms` reduced by >= 8ms
- [ ] No visual jank in 60-second gameplay
- [ ] FPS improvement measured (expect ~60-75 FPS cumulative with Phase 1)

---

### Phase 3: VirGL Pipeline Tuning

**Depends on**: Phase 0 (benchmark harness), Phase 1 (cores pinned)
**Expected gain**: +5-20 FPS
**Risk**: Low-Medium

---

#### Task 3A: glxgears Pipeline Ceiling

**What**: Run `benchmark-pipeline.sh` (from Task 0C) with CPU affinity applied (Phase 1). This establishes the maximum FPS the VirGL pipeline can achieve regardless of workload.

**Decision gate**:
- If glxgears < 120 FPS → pipeline is the ceiling, proceed to Phase 3B-3C
- If glxgears >= 120 FPS → RuneLite is the ceiling, skip to Task 3D

**Test**: N/A (manual benchmark, results logged to debug server)

---

#### Task 3B: VirGL Environment Variable Tuning

**What**: Test Mesa/VirGL environment variables that may improve batching and reduce IPC overhead.

**Variables to test** (one at a time, measure each):

| Variable | Value | Expected Effect |
|----------|-------|-----------------|
| `VIRGL_RENDERER_THREAD=1` | Enable threaded rendering in virgl server | Decouple decode from execute |
| `VIRGL_RENDERER_ASYNC=1` | Async command submission | Reduce round-trip blocking |
| `MESA_GLSL_CACHE_DISABLE=0` | Enable shader cache | Reduce per-frame shader compile |
| `MESA_SHADER_CACHE_MAX_SIZE=256M` | Increase shader cache | More shaders cached |
| `LP_NUM_THREADS=4` | (for llvmpipe only) | N/A if using virpipe |

**Where**: `launch-runelite.sh` — add env vars to the proot environment.

**Implementation**: Add each variable behind a feature flag (env var `RLT_VIRGL_TUNE=1`) so it can be toggled without code changes. Log the active configuration via `VirglEnvironmentProfiler` (Task 0D).

**Test**: `VirglTuningTest` — Verify env vars are set correctly in the proot environment. Verify feature flag toggle works. Compare FPS before/after via `PipelineBenchmark`.

**Files**:
- `launch-runelite.sh` — Add env vars
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/VirglTuningTest.kt`

**LoC estimate**: ~15 lines shell, ~40 lines test

---

#### Task 3C: Server-Side Zink Evaluation

**What**: Check if the Immortalis-G720's Vulkan driver exposes the features Zink requires, and if so, test replacing ANGLE with Zink on the Termux-native virgl_test_server.

**Prerequisite check** (must pass before implementation):
```bash
# On device, in Termux-native (not proot)
vulkaninfo --summary 2>/dev/null | grep -E "apiVersion|deviceName|fillModeNonSolid|shaderClipDistance|logicOp"
```

**Decision gate**:
- If `fillModeNonSolid`, `shaderClipDistance`, AND `logicOp` all = TRUE → proceed with Zink swap
- If ANY = FALSE → skip this task, Zink is not viable on this GPU

**Implementation** (if viable):
1. Install `mesa-vulkan-icd-wrapper` in Termux-native
2. Set `GALLIUM_DRIVER=zink` for virgl_test_server instead of `--angle-gl`
3. Verify rendering correctness (no artifacts in glxgears)
4. Measure FPS improvement

**Risk**: Medium — G720 may be missing features (confirmed missing on older G78).

**Test**: `ServerSideZinkTest` — Shell script that:
1. Checks Vulkan feature availability
2. Starts virgl_test_server with Zink backend
3. Runs glxgears for 30 seconds
4. Compares FPS against ANGLE baseline

**Files**:
- New: `runelite-tablet/app/src/main/assets/scripts/test-server-zink.sh`
- `setup-gpu-mali.sh` — Add Zink backend option behind feature flag

**LoC estimate**: ~80 lines shell, ~30 lines test

---

#### Task 3D: RuneLite GL Call Profiling (if glxgears >= 120 FPS)

**What**: If the pipeline ceiling is not the bottleneck, profile RuneLite's GL call patterns using GALLIUM_HUD.

**Implementation**:
```bash
GALLIUM_HUD="fps,draw-calls,prims-emitted" java -jar RuneLite.jar
```

Parse HUD output to determine draw calls/frame. If > 200, the bottleneck is RuneLite's GL verbosity, not the pipeline.

**Test**: N/A (diagnostic, results logged)

---

#### Phase 3 Verification

- [ ] glxgears FPS documented (pipeline ceiling established)
- [ ] VirGL env var impact measured (each variable independently)
- [ ] Server-side Zink feasibility determined (Vulkan feature check)
- [ ] If Zink viable: FPS improvement measured vs ANGLE baseline
- [ ] FPS improvement measured (expect ~65-90 FPS cumulative)

---

### Phase 4: ptrace Reduction

**Depends on**: Phase 0 (PtraceOverheadEstimator), Phase 1 (cores pinned)
**Expected gain**: +15-20 FPS (if seccomp-bpf available)
**Risk**: Medium — kernel feature availability varies

---

#### Task 4A: Kernel Capability Check

**What**: Check if the device kernel supports seccomp-bpf and user namespaces, which could replace proot's ptrace-based syscall interception.

**Implementation**:
```bash
# Check kernel config
zcat /proc/config.gz 2>/dev/null | grep -E "SECCOMP|USER_NS" || echo "config.gz not available"
# Check proot seccomp support
proot --help 2>&1 | grep -i seccomp
# Check user namespace support
unshare --user --map-root-user echo "user_ns works" 2>&1
```

**Decision gate**:
- If proot supports `--seccomp` AND kernel has `CONFIG_SECCOMP_FILTER=y` → proceed to Task 4B
- If user namespaces work → evaluate as proot replacement (Task 4C)
- If neither → Phase 4 is blocked; document ceiling

**Test**: `KernelCapabilityTest` — Parse output of capability check script. Verify correct detection of seccomp/user-ns support.

**Files**:
- New: `runelite-tablet/app/src/main/assets/scripts/check-kernel-caps.sh`
- `runelite-tablet/app/src/test/java/com/runelitetablet/perf/KernelCapabilityTest.kt`

**LoC estimate**: ~30 lines shell, ~40 lines test

---

#### Task 4B: proot seccomp-bpf Mode

**What**: If available, switch proot to seccomp-bpf mode. Seccomp filters syscalls in kernel space (~5us) instead of ptrace user-kernel round-trips (~50us).

**Implementation**: In `launch-runelite.sh`, add `--seccomp` flag to proot invocation:
```bash
proot --seccomp -0 -w /root -b /dev -b /proc -b /sys ...
```

**Verification**: `PtraceOverheadEstimator` should show `kernel_ratio` dropping from ~0.54 to ~0.20.

**Test**: Run RuneLite with seccomp mode. Verify no crash. Verify FPS improvement. Verify syscall patterns still work (some syscalls may behave differently under seccomp).

**Files**: `launch-runelite.sh`

**LoC estimate**: ~5 lines shell

---

#### Task 4C: User Namespace Evaluation (if available)

**What**: If user namespaces work, evaluate `unshare` as a proot replacement. This eliminates ALL ptrace overhead.

**Implementation**: Create a test script that launches a simple program via `unshare` instead of proot, verifies path remapping works, and measures the kernel time ratio.

**Test**: Compare `stime/utime` ratio of a test process under proot vs unshare.

**Files**: New: `test-user-ns.sh`

**LoC estimate**: ~40 lines shell

---

#### Phase 4 Verification

- [ ] Kernel capabilities documented
- [ ] If seccomp available: `kernel_ratio` reduced from ~0.54 to <= 0.25
- [ ] If user-ns available: `kernel_ratio` reduced to < 0.15
- [ ] FPS improvement measured (expect ~75-100 FPS cumulative with all phases)
- [ ] No RuneLite functionality regression (auth, input, rendering)

---

### Part 1 Exit Criteria

All of the following must be true before proceeding to Part 2:
- [ ] All Phase 0 observability tools operational
- [ ] Phase 1-4 optimizations applied (or documented as not applicable)
- [ ] Best achievable FPS documented with full `PERF_SUMMARY` data
- [ ] If FPS >= 120: **DONE** — verification gate passed, no Part 2 needed
- [ ] If FPS < 120: Root cause documented (which bottleneck remains), proceed to Part 2

---

## Part 2: Direct Android Surface (Target: 120 FPS)

**Prerequisite**: Part 1 complete with documented ceiling < 120 FPS.
**Rationale**: The proot+VirGL architecture has irreducible overhead from ptrace and IPC serialization. To reach 120 FPS, the rendering path must bypass VirGL entirely.

### Phase 5: AHardwareBuffer Zero-Copy VirGL Bypass

**Full implementation details**: See `2026-03-16-presentation-pipeline-120fps-spec.md` Phase 3.

**Summary**: Allocate shared `AHardwareBuffer`s between virgl_test_server and the Xlorie renderer. VirGL renders directly into the AHB via FBO attachment. Xlorie samples the AHB as an `EGLImage` texture. Synchronization via native fence FDs (zero CPU cost).

This eliminates:
- `glReadPixels` (2-5ms)
- Socket busy-wait (0.1-1ms)
- CPU memcpy (1-2ms)
- X11 ShmPutImage (1-2ms)
- `glTexSubImage2D` re-upload (5-15ms)

**Expected gain**: +20-40 FPS on top of Part 1 ceiling.

**Test requirements**:
- `AhbAllocatorTest` — Verify AHB allocation with correct format/usage flags
- `AhbSharingTest` — Verify AHB handle send/receive via Unix socket
- `FenceSyncTest` — Verify native fence creation, transfer, and GPU-side wait
- `ZeroCopyRendererTest` — Integration test verifying the full zero-copy path produces correct pixels

**Files** (from existing spec):
- `renderer.c` — AHB allocation, import, fence receive
- `activity.c` — Control channel setup
- `buffer.c` — New AHB buffer type
- New: `ahb_bridge.c` — AHB sharing protocol

---

### Phase 6: Custom GL Command Proxy (if Phase 5 insufficient)

**Prerequisite**: Phase 5 implemented but FPS still < 120 FPS.
**Rationale**: If AHB zero-copy eliminates the presentation overhead but content production is still too slow (RuneLite producing < 120 damage events/sec through VirGL IPC), the VirGL IPC protocol itself must be replaced.

---

#### Task 6A: GL Command Capture Design

**What**: Design a custom protocol that captures RuneLite's GL calls at the Mesa driver level and streams them in batched, async packets to an Android-native renderer.

**Architecture**:
```
RuneLite GL3 calls
  → Custom Mesa Gallium driver (replaces virpipe)
    → Batch N GL commands into single packet
    → Async write to Unix domain socket (non-blocking)
  → Android-native renderer (in-app)
    → Receive packet
    → Replay GL commands via Vulkan (mali Vulkan driver, Bionic-linked)
    → Render to SurfaceView
```

**Key difference from VirGL**: VirGL is synchronous per-command. This protocol batches commands and uses async IPC with back-pressure, eliminating the per-command round-trip overhead.

**Design deliverable**: Architecture document specifying:
1. Packet format (binary, versioned header + command array)
2. Batching strategy (flush on `glSwapBuffers`, `glFlush`, or when batch exceeds 64KB)
3. Async IPC protocol (Unix domain socket, non-blocking writes, eventfd for signaling)
4. Back-pressure mechanism (ring buffer with writer/reader cursors)
5. Replay engine API (Vulkan command buffer recording from GL command stream)

**Test**: N/A (design document, not code)

---

#### Task 6B: Prototype Passthrough Driver

**What**: Build a minimal Mesa Gallium driver that captures `glClear`, `glDrawElements`, and `glSwapBuffers` and writes them to a socket. This validates the capture + replay concept before building the full protocol.

**Test**: `PassthroughDriverTest` — Render a triangle via the passthrough driver. Verify the command stream contains the expected commands. Verify the replay produces the correct framebuffer content.

---

#### Task 6C: Full Protocol Implementation

**What**: Implement the complete GL command proxy with all RuneLite-relevant GL calls, batching, and async IPC.

**Test**: Run RuneLite through the proxy. Verify correct rendering. Measure FPS improvement.

---

#### Phase 6 Verification

- [ ] GL command proxy renders RuneLite correctly (visual comparison)
- [ ] FPS >= 120 sustained for 60 seconds
- [ ] No rendering artifacts
- [ ] Input latency not degraded

---

### Part 2 Exit Criteria

- [ ] FPS >= 120 sustained on logged-in RuneLite session
- [ ] All `PERF_SUMMARY` metrics within target
- [ ] No regression in input, auth, or visual quality
- [ ] Performance data captured and archived in `runelite-tablet/docs/logs/`

---

## Execution Order

```
Phase 0A-0G (Observability) ─── all tasks can run in parallel
       │
       ▼
Phase 1A-1B (CPU Affinity) ─── depends on 0B (monitor)
       │
       ├──► Phase 2A-2C (Presentation Fixes) ─── can run in parallel with Phase 3
       │
       └──► Phase 3A (glxgears benchmark) ─── depends on 0C (harness) + Phase 1
                │
                ├──► Phase 3B (VirGL env tuning) ─── depends on 3A results
                ├──► Phase 3C (Server-side Zink) ─── depends on 3A results
                └──► Phase 3D (GL profiling) ─── only if 3A shows pipeline is not ceiling
       │
       ▼
Phase 4A (Kernel check) ─── depends on 0E (ptrace estimator)
       │
       ├──► Phase 4B (seccomp-bpf) ─── if kernel supports it
       └──► Phase 4C (user namespace) ─── if kernel supports it
       │
       ▼
Part 1 Exit Gate ─── measure, document, decide
       │
       ▼ (only if < 120 FPS)
Phase 5 (AHB Zero-Copy) ─── from existing spec
       │
       ▼ (only if still < 120 FPS)
Phase 6A-6C (GL Command Proxy) ─── most ambitious path
```

## Key Metrics Dashboard

| Metric | Source | Baseline | Part 1 Target | Final Target |
|--------|--------|----------|---------------|--------------|
| damage-triggered redraws | LorieNative (ADB) | 34-36 FPS | 80+ FPS | 120 FPS |
| estimated_fps | gles-renderer (debug server) | 38-54 FPS | 80+ FPS | 120 FPS |
| content_util | gles-renderer (debug server) | 100% | 100% | 100% |
| avg_inter_frame_ms | gles-renderer (debug server) | 22-27ms | <= 12ms | <= 8.3ms |
| RuneLite CPU core | CpuAffinityMonitor | CPU 0 (little) | CPU 4-7 (big/prime) | CPU 4-7 |
| RuneLite kernel_ratio | PtraceOverheadEstimator | 0.54 | <= 0.25 | <= 0.15 |
| VirGL CPU core | CpuAffinityMonitor | CPU 1 (little) | CPU 4-7 (big/prime) | CPU 4-7 |
| VirGL kernel_ratio | PtraceOverheadEstimator | 0.58 | <= 0.25 | <= 0.15 |
| avg_fence_ms | gles-renderer (debug server) | 2.5-2.9ms | <= 3ms | <= 3ms |
| wait_content_pct | renderer.c (new) | unknown | < 30% | < 10% |
| glxgears pipeline FPS | PipelineBenchmark | unknown | documented | documented |

## Risk Registry

| Risk | Phase | Likelihood | Impact | Mitigation |
|------|-------|-----------|--------|------------|
| `taskset` not available in Termux | 1 | Low | Phase 1 blocked | Use `sched_setaffinity` via JNI instead |
| Android EAS re-migrates pinned processes | 1 | Medium | Partial FPS loss | Task 1B: periodic re-pin watchdog |
| Kernel lacks seccomp-bpf | 4 | Medium | Phase 4 blocked | Accept ptrace overhead ceiling |
| G720 missing Zink Vulkan features | 3C | High | Server-side Zink blocked | Skip 3C, rely on other phases |
| VirGL env vars cause rendering artifacts | 3B | Low | Visual regression | Test each variable independently, revert on artifacts |
| AHB sharing fails cross-process | 5 | Low | Phase 5 blocked | Internal-hybrid runs server in same process |
| RGBA EGLImage fails on Mali | 2A | Medium | Phase 2A blocked | Try all AHARDWAREBUFFER_FORMAT variants |
| Total FPS < 120 after all phases | - | Medium | Goal not met | Document ceiling, accept best achievable |

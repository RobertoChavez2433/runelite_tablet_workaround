# VirGL Rendering Test Pipeline — Spec

**Date**: 2026-03-09 | **Status**: Approved | **Session**: 49

## Problem Statement

RuneLite's GPU plugin renders a black 3D world when running through VirGL on Mali (ANGLE OpenGL ES 3.2). Root cause: the plugin uses reversed-Z depth (`glDepthFunc(GL_GREATER)` + `glClearDepth(0)`) which requires `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`. LWJGL's `OpenGL45` check fails because VirGL natively supports GL 4.3 (not 4.5), so `glClipControl` is never called. `MESA_NO_ERROR=1` silently swallows the error.

Debugging this took 6+ hours of iterative testing through the actual game client. We need a standalone developer tool that:
1. Tests every GL feature RuneLite needs without launching the game
2. Instruments every stage of the 11-hop rendering pipeline
3. Eliminates silent failures with exhaustive logging
4. Provides LD_PRELOAD shims to fix the reversed-Z depth issue
5. Produces persistent, timestamped results for comparison across runs

## Architecture

Three tiers, all running inside proot Ubuntu through the real VirGL pipeline (virpipe → socket → virgl_test_server → ANGLE → Mali GPU).

### Tier 1: Custom GL Test Harness

A single C program (`gl_test_harness.c`) compiled inside proot with gcc + GLFW. Runs 9 test modules sequentially, each self-contained: setup → execute → capture framebuffer via `glReadPixels` (FBO mode to avoid XGetImage BadMatch crash) → validate → log structured JSON results + PNG screenshots.

### Tier 2: LD_PRELOAD Depth Fix Shims

Two `.so` libraries compiled in proot, tested by the harness in Module 4:
- **Shim A** (`fix_inject_clipcontrol.so`): Intercepts `glClearDepth(0)`, calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` via dlsym
- **Shim B** (`fix_flip_depth.so`): Intercepts `glDepthFunc(GL_GREATER→GL_LESS)` and `glClearDepth(0→1)`

### Tier 3: Piglit GL 3.3 Sweep

Piglit built from source inside proot. Runs GL 3.3 core tests + targeted extension tests in `-fbo` mode. Results as JSON/HTML.

## Standalone Developer Tool — No App Integration

The test pipeline is **completely independent** of the Android app. No changes to SetupOrchestrator, ScriptManager, TermuxCommandRunner, or any app code. Deployed via `adb push`, executed via `adb shell`, results pulled via `adb pull`.

### Repository Location

```
runelite-tablet/gl-tests/
├── src/
│   ├── gl_test_harness.c          # Main harness — all 9 modules
│   ├── gl_test_log.h              # Logging macros, JSON output, glGetError wrapper
│   ├── fix_inject_clipcontrol.c   # LD_PRELOAD Shim A
│   ├── fix_flip_depth.c           # LD_PRELOAD Shim B
│   ├── test_shaders.h             # GLSL shader source strings
│   └── stb_image_write.h          # Single-header PNG writer (vendored)
├── scripts/
│   ├── deploy.sh                  # Push everything to device via adb
│   ├── install-deps.sh            # Install gcc, GLFW, Piglit deps in proot
│   ├── build.sh                   # Compile harness + shims inside proot
│   ├── run-tests.sh               # Master orchestrator (--quick or --full)
│   └── install-piglit.sh          # Build Piglit from source in proot
└── README.md                      # Usage instructions
```

### Deployment (from dev machine)

```bash
cd runelite-tablet/gl-tests
./scripts/deploy.sh    # adb push to ~/gl-tests/ on device
```

`deploy.sh` does:
1. `adb push src/ ~/gl-tests/src/`
2. `adb push scripts/ ~/gl-tests/scripts/`
3. `chmod +x` all scripts

### Execution (via adb)

```bash
# Quick run (Tier 1 + 2 only, ~2 min)
adb shell "run-as com.termux sh -c '~/gl-tests/scripts/run-tests.sh --quick'"

# Full run (Tier 1 + 2 + Piglit, ~30 min)
adb shell "run-as com.termux sh -c '~/gl-tests/scripts/run-tests.sh --full'"
```

### Results — Persistent, Timestamped, Never Overwritten

```
~/gl-tests/results/
├── run-20260309-221500/
│   ├── SUMMARY.md                    # Human-readable report
│   ├── summary.json                  # Machine-readable per-test results + timing
│   ├── environment.json              # Every env var, library version, system info
│   ├── gl-caps.json                  # Full GL state dump (extensions, limits)
│   ├── function-pointers.json        # Every probed GL function (name → address|null)
│   ├── screenshots/                  # FBO captures per test (PNG)
│   ├── gallium.xml                   # Complete Gallium command trace
│   ├── mesa.log                      # Mesa debug log (MESA_LOG_LEVEL=debug)
│   ├── libgl-debug.log               # LIBGL_DEBUG=verbose output
│   ├── virgl-server.log              # VirGL debug (all flags)
│   ├── tgsi-shaders.log              # TGSI shader dumps (ST_DEBUG=tgsi)
│   ├── shaders/                      # Compiled shader cache
│   ├── angle.log                     # ANGLE logcat output
│   ├── vulkan-validation.log         # Vulkan validation layer output
│   ├── mali.log                      # Mali driver logcat output
│   ├── proot.log                     # PROOT_VERBOSE output
│   ├── xdpyinfo.txt                  # X11 server capabilities
│   ├── perf-monitor.csv              # CPU/memory/thermal samples (100ms)
│   ├── gl-errors.log                 # Every glGetError non-zero result
│   ├── piglit-results/               # Piglit JSON + HTML (--full only)
│   └── timing.json                   # Per-stage latency (nanosecond precision)
├── run-20260310-143000/
│   └── ...
└── latest -> run-20260309-221500/    # Symlink to most recent
```

### Pulling Results

```bash
adb shell "run-as com.termux sh -c 'tar czf /tmp/gl-results.tar.gz ~/gl-tests/results/latest/'"
adb pull /data/data/com.termux/files/usr/tmp/gl-results.tar.gz ./gl-test-results/
```

## Test Modules (Tier 1)

### Module 1: GL Capability Dump
Creates GL context, dumps every `glGetString`, every `glGetIntegerv` limit (~30 values), every extension (sorted, one per line), and probes ~30 critical GL function pointers via `glXGetProcAddressARB`. Writes `gl-caps.json` and `function-pointers.json`. No rendering — pure state query. Runs first so baseline data exists even if later modules crash.

### Module 2: GLSL 330 Shader Compilation
Compiles RuneLite's exact shader features as separate mini-shaders: `#version 330`, `layout(location=N) in`, `layout(std140) uniform`, `sampler2DArray`, `noperspective centroid`, `flat` qualifier, `inverse(mat3)`, `textureLod()`, `textureSize()`. Logs compile status + info log (even on success to catch warnings) + link status. Captures TGSI translations via `ST_DEBUG=tgsi`.

### Module 3: glClipControl Probe
Three sub-tests:
- 3a: Check if `glClipControl` function pointer is non-NULL
- 3b: If non-NULL, call it and check `glGetError()` — `GL_NO_ERROR` or `GL_INVALID_OPERATION`?
- 3c: If no error, query `GL_CLIP_DEPTH_MODE` and `GL_CLIP_ORIGIN` to verify state actually changed
Logs exact function pointer address, error codes, and state values.

### Module 4: Reversed-Z Depth Buffer
Renders two overlapping triangles (red behind blue) with reversed-Z setup: `glDepthFunc(GL_GREATER)`, `glClearDepth(0)`, float depth buffer. Three runs:
- 4a: No shim (baseline — expected to fail without working glClipControl)
- 4b: With Shim A (`fix_inject_clipcontrol.so`) loaded via `dlopen()` at runtime
- 4c: With Shim B (`fix_flip_depth.so`) loaded via `dlopen()` at runtime
Each run: capture color framebuffer (PNG) + read back depth buffer (min/max/mean). Correct result = blue triangle in front of red.

### Module 5: sampler2DArray Texture Sampling
Creates a 3-layer texture array (red/green/blue layers), renders three quads each sampling a different layer. Captures framebuffer, validates pixel colors match expected layers. Tests RuneLite's exact texture path.

### Module 6: noperspective Interpolation
Renders a perspective-projected quad with `noperspective` varying, then again with `smooth`. Compares output — they should differ. If identical, VirGL is dropping the `noperspective` qualifier during TGSI translation. Captures both framebuffers.

### Module 7: FBO Render + Blit
Creates FBO with color + depth attachments, renders a colored scene, `glBlitFramebuffer` to second FBO. Reads back both, compares CRC32 checksums — they should match. This is RuneLite's exact present path.

### Module 8: RuneLite Scene Emulation
Integration test combining: reversed-Z depth + sampler2DArray textures + noperspective varyings + FBO blit + UBO + winning LD_PRELOAD shim. Renders textured cubes at varying depths with fog. If this passes, RuneLite's GPU plugin should work.

### Module 9: Performance Baseline
Renders Module 8's scene for 300 frames. Measures:
- Frame time: min/max/mean/p95/p99
- `glReadPixels` latency per frame
- Total GL calls per frame
- FPS at current resolution
- FPS at half resolution (re-creates context)
Writes `timing.json`.

## LD_PRELOAD Depth Fix Shims (Tier 2)

### Shim A: `fix_inject_clipcontrol.so`

Intercepts `glClearDepth(GLdouble depth)`. When `depth == 0.0`:
1. Calls `glXGetProcAddressARB("glClipControl")` to get function pointer
2. If non-NULL: calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`
3. Logs: `[SHIM-A] glClipControl injected, ptr=0x..., glGetError()=0x...`
4. One-time injection (atomic flag, not every frame)
5. If NULL: logs `[SHIM-A] FATAL: glClipControl function pointer is NULL`
6. Passes through original `glClearDepth(0.0)` unchanged

Preserves RuneLite's reversed-Z design exactly as intended.

### Shim B: `fix_flip_depth.so`

Intercepts two functions:
- `glDepthFunc(func)`: `GL_GREATER` → `GL_LESS`, `GL_GEQUAL` → `GL_LEQUAL`, others pass through
- `glClearDepth(depth)`: `0.0` → `1.0`, others pass through

Logs every interception with `[SHIM-B]` prefix. Undoes reversed-Z entirely. Fallback if Shim A can't work.

### Shared properties
- `dlsym(RTLD_NEXT, ...)` for original function resolution
- Structured stderr logging with shim prefix
- Thread-safe (atomic flag for one-time init)
- Minimal surface area — no other GL calls intercepted

### Testing protocol
Module 4 uses `dlopen()` to load each shim at runtime (not `LD_PRELOAD` env var) to A/B test within a single process.

## Piglit Integration (Tier 3)

### Installation
`install-piglit.sh` installs build dependencies (`cmake`, `waffle`, `python3-numpy`, `freeglut3-dev`, `libwaffle-dev`) and builds Piglit from source. Cached in `~/piglit/`.

### Test Groups

| Group | What | Test Count | Maps To |
|-------|------|-----------|---------|
| GL 3.3 Core | Full GL 3.3 spec | ~2000 | RuneLite minimum GL requirement |
| `spec/arb_clip_control` | Reversed-Z, clip depth mode | ~12 | Black screen root cause |
| `spec/ext_texture_array` | sampler2DArray | ~50 | Game texture atlas |
| `spec/arb_framebuffer_object` | FBO create/blit/attachments | ~100 | RuneLite present path |
| `spec/arb_depth_buffer_float` | Float depth buffers | ~20 | Reversed-Z precision |
| `spec/glsl-3.30` | GLSL 330 features | ~200 | Shader compatibility |
| VirGL smoke tests | virglrenderer CI subset | ~100 | General VirGL health |

### Execution
All tests run with `-fbo` flag (avoids XGetImage BadMatch crash). Results output as JSON + HTML summary.

### Runtime
- `--quick` (Tier 1+2 only): ~2 minutes
- `--full` (Tier 1+2+3): ~30 minutes

## Exhaustive Instrumentation — No Silent Failures

Philosophy: **every boundary crossing, every state change, every resource allocation, every error path gets a log line.** If something fails silently, it's a bug in our instrumentation.

### Stage 0-2 (Shell → Termux → proot entry)
- Script entry timestamp, `$SHELL`, `$PATH`, `$PREFIX`, `$HOME`
- Every `kill`/`pkill` result (what was killed, what wasn't found)
- PulseAudio start: PID, port bind success
- Termux:X11 start: PID, socket path, `ls -la` of socket
- VirGL server start: exact command line, PID, socket path
- Socket wait loop: every retry iteration (attempt N/30, socket exists? permissions?)
- Termux:X11 preferences: each call + response
- Every environment variable set (name=value)
- Process tree snapshot before entering proot

### Stage 3 (proot)
- proot command line (all flags, bind mounts, rootfs path)
- `PROOT_VERBOSE=1` output captured
- Inside proot: `uname -a`, `cat /etc/os-release`, `id`
- VirGL socket visibility: `ls -la /tmp/.virgl_test`, `stat`, socket type
- X11 socket visibility: `ls -la /tmp/.X11-unix/X0`
- Disk space: `df -h /`, `df -h /tmp`
- Memory: `free -m`
- CPU: `nproc`, model name
- DNS resolution test: `getent hosts github.com`
- Package verification: `dpkg -l | grep -E 'mesa|libgl|glfw|gcc'` with versions

### Stage 4 (GL context creation)
- `LIBGL_DEBUG=verbose` — every library dlopen attempt, success/failure, path
- GLX visual selection: requested attributes, matched visual ID, depth, RGBA sizes
- GL context creation: success/failure, version requested vs actual
- `glGetString()` for all 4 string queries
- **Full extension list** — every extension, one per line, sorted
- **Every `glGetIntegerv` limit** (~30 values):
  - `GL_MAX_TEXTURE_SIZE`, `GL_MAX_ARRAY_TEXTURE_LAYERS`, `GL_MAX_TEXTURE_IMAGE_UNITS`
  - `GL_MAX_DRAW_BUFFERS`, `GL_MAX_COLOR_ATTACHMENTS`, `GL_MAX_RENDERBUFFER_SIZE`
  - `GL_MAX_VERTEX_ATTRIBS`, `GL_MAX_UNIFORM_BLOCK_SIZE`, `GL_MAX_SAMPLES`
  - `GL_DEPTH_BITS`, `GL_STENCIL_BITS`, `GL_MAX_VIEWPORT_DIMS`
  - `GL_MAX_CLIP_DISTANCES`
- **Function pointer probes** for ~30 critical functions via `glXGetProcAddressARB()`: log name → address or NULL
- `glGetError()` called and logged after EVERY GL call (not just end of frame)
- `MESA_LOG_FILE` + `MESA_LOG_LEVEL=debug`

### Stage 5 (Mesa virpipe / Gallium)
- `GALLIUM_TRACE=gallium.xml` — complete Gallium pipe_context trace
- `GALLIUM_PRINT_OPTIONS=1` — dump all active Gallium env vars
- `GALLIUM_DUMP_CPU=1` — CPU capabilities
- `ST_DEBUG=tgsi` — every TGSI shader generated
- `TGSI_PRINT_SANITY=1` — extra TGSI validation
- `MESA_SHADER_CAPTURE_PATH=shaders/` — save compiled shaders
- Socket connection: log connect() success/fail, errno

### Stage 6 (VirGL server)
- `VIRGL_DEBUG=tgsi,shader,shader_compile,stream,resource,query` — maximum verbosity:
  - `tgsi`: every TGSI shader received from client
  - `shader`: every translated GLSL shader sent to host
  - `shader_compile`: compilation errors/warnings
  - `stream`: every decoded command with parameters
  - `resource`: every GPU resource create/destroy with dimensions and format
  - `query`: every GL query operation
- Server stderr captured to dedicated log file
- Server PID alive check before each test module (detect crashes)
- If server dies: capture exit code, signal, last 50 lines of log

### Stage 7 (ANGLE)
- `adb shell setprop debug.angle.markers 1`
- `adb logcat -s ANGLE` captured during test
- Vulkan validation: `adb shell setprop debug.vulkan.layers VK_LAYER_KHRONOS_validation`
- Vulkan validation output captured

### Stage 8 (GPU)
- `adb logcat -s mali` captured during test
- Thermal: `cat /sys/class/thermal/thermal_zone*/temp` before and after
- GPU clock (sysfs, if readable)

### Stage 9 (Readback)
- Per-test `glReadPixels` wall time (nanosecond precision via `clock_gettime`)
- Pixel validation: CRC32 checksum, non-zero pixel count, color histogram
- Depth buffer reads: min/max/mean depth values
- Socket throughput: bytes/time for texture upload tests

### Stage 10-11 (X11 → Display)
- `xdpyinfo` full output (screen count, depth, visual list, **MIT-SHM availability**)
- `xlsclients` — connected X11 clients
- `adb exec-out screencap -p` — Android-level screenshot per test
- Frame timing: wall clock per frame

### Error Handling
- Every GL call wrapped with `glGetError()` → log `[GLERROR] function: GL_INVALID_ENUM (0x0500)`
- Every system call checked: `if (ret < 0) log("SYSCALL_ERROR: %s errno=%d (%s)")`
- Every file open/write: log path, success/fail, bytes written
- Every `dlsym()` probe: log symbol name, resolved address or NULL
- Signal handler for SIGSEGV/SIGBUS: writes crash dump before dying
- VirGL server watchdog: background check every second

## SUMMARY.md Output Format

```markdown
# GL Test Results — YYYY-MM-DD HH:MM:SS

## Environment
- Device: Samsung Tab S10 Ultra (Immortalis-G720 MC12)
- Mesa: 25.2.8 | VirGL: 1.3.0 | ANGLE: 2.1.24923
- GL: X.X Compat (override) | GLSL: NNN (override)

## Tier 1: Feature Tests
| # | Test | Result | Notes |
|---|------|--------|-------|
| 1 | GL Capability Dump | PASS/FAIL | ... |
| 2 | GLSL 330 Compilation | PASS/FAIL | ... |
| 3 | glClipControl Probe | PASS/FAIL | ptr, error code, state values |
| 4a | Reversed-Z (no shim) | PASS/FAIL | pixel analysis |
| 4b | Reversed-Z (Shim A) | PASS/FAIL | pixel analysis |
| 4c | Reversed-Z (Shim B) | PASS/FAIL | pixel analysis |
| 5 | sampler2DArray | PASS/FAIL | layer validation |
| 6 | noperspective | PASS/FAIL | interpolation comparison |
| 7 | FBO Blit | PASS/FAIL | CRC match |
| 8 | Scene Emulation | PASS/FAIL | using winning shim |
| 9 | Performance | — | FPS, frame time stats |

## Tier 2: Shim Recommendation
**Winner**: Shim A or Shim B (with reasoning)

## Tier 3: Piglit (--full only)
- GL 3.3 Core: N/M pass, X fail, Y skip
- Per-extension results table
- Link to piglit-results/summary.html

## Key Findings
1. ...
2. ...

## Performance Metrics
- Mean frame time: Xms
- P95 frame time: Xms
- glReadPixels latency: Xms
- FPS at native res: X
- FPS at half res: X
```

## Affected Packages

| Package | Impact |
|---|---|
| `shell` (assets/scripts/) | New scripts in `gl-tests/scripts/`. Follow shell constraints. |
| `setup` | **No changes.** |
| `termux` | **No changes.** |
| `auth` | **No changes.** |
| `ui` | **No changes.** |

## Constraint Compliance

| Constraint | How We Comply |
|---|---|
| Shell: Proot exit codes unreliable | Harness writes SUMMARY.md + summary.json as success markers. Scripts check file existence, never exit codes. |
| Shell: Windows git CRLF breaks shebangs | `.gitattributes` entry: `gl-tests/**/*.sh eol=lf`, `gl-tests/src/*.c eol=lf`. Deploy script strips `\r`. |
| Shell: All `"` inside `bash -c "..."` must be escaped | C source deployed as files, not inline. Scripts use `proot-distro login ubuntu -- bash script.sh`, not nested `bash -c`. |
| Shell: proot `--kill-on-exit` kills children | Test harness is a single process. No child spawning. |
| Setup: No impact | Zero app code changes. |

## New Files

All in `runelite-tablet/gl-tests/` (standalone, not in app assets):
- `src/gl_test_harness.c` — Main harness (9 modules)
- `src/gl_test_log.h` — Logging infrastructure
- `src/fix_inject_clipcontrol.c` — Shim A
- `src/fix_flip_depth.c` — Shim B
- `src/test_shaders.h` — GLSL source strings
- `src/stb_image_write.h` — Vendored PNG writer
- `scripts/deploy.sh` — adb push to device
- `scripts/install-deps.sh` — proot dependency installation
- `scripts/build.sh` — Compile harness + shims
- `scripts/run-tests.sh` — Master orchestrator
- `scripts/install-piglit.sh` — Piglit build
- `README.md` — Usage docs
- `.gitattributes` update for `eol=lf`

## Success Criteria

1. `run-tests.sh --quick` completes in under 5 minutes with all Tier 1 tests producing results
2. SUMMARY.md clearly shows which LD_PRELOAD shim fixes the reversed-Z depth issue
3. Every pipeline stage produces log output (no silent gaps)
4. Results persist across runs and are easy to pull to dev machine
5. `run-tests.sh --full` Piglit results provide a GL 3.3 compatibility baseline for the VirGL setup

## Research References

- VirGL capability dump: `.claude/research/virgl-capabilities-dump.md`
- GPU rendering research: `.claude/research/gpu-rendering-options.md`
- RuneLite GPU plugin analysis: research agents (Session 49) confirmed all shaders use `#version 330`, no compute shaders, reversed-Z depth via `glClipControl`
- Pipeline mapping: research agent (Session 49) mapped all 11 stages with IPC mechanisms, data formats, and instrumentation points
- GL test frameworks: research agent (Session 49) evaluated Piglit, apitrace, glmark2, PyOpenGL, GALLIUM_HUD, LD_PRELOAD approaches

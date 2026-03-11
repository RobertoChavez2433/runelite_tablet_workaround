# VirGL Rendering Test Pipeline

Automated GPU capability testing for RuneLite on Android tablets via Termux + proot + VirGL.

## Prerequisites

- Android tablet with Termux installed
- `proot-distro` with Ubuntu installed (`proot-distro install ubuntu`)
- Termux:X11 installed
- `virgl_test_server_android` available in Termux PATH
- ADB connection to device (USB or wireless)
- `allow-external-apps = true` in `~/.termux/termux.properties`

## Quick Start

### 1. Deploy to device

```bash
./scripts/deploy.sh
# Multi-device: ./scripts/deploy.sh -s DEVICE_SERIAL
```

### 2. Install dependencies (first time only)

```bash
adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc '~/gl-tests/scripts/install-deps.sh'"
```

### 3. Build test harness

```bash
adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc '~/gl-tests/scripts/build.sh'"
```

### 4. Run tests

```bash
# Quick run (Tier 1 + 2: capability probes, shader tests, shim comparison)
adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc '~/gl-tests/scripts/run-tests.sh --quick'"

# Full run (adds Tier 3: Piglit GL 3.3 conformance sweep)
adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc '~/gl-tests/scripts/run-tests.sh --full'"
```

### 5. Pull results

```bash
adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc 'cat \$PREFIX/tmp/gl-results-latest.tar.gz'" > gl-results.tar.gz
tar xzf gl-results.tar.gz -C gl-results/
```

## Test Modules

| Module | Tier | Description |
|--------|------|-------------|
| 1 | 1 | GL capability dump (extensions, function pointers, limits) |
| 2 | 1 | GLSL 330 shader compilation (all RuneLite-critical shaders) |
| 3 | 1 | glClipControl probe (pointer, call, state query) |
| 4 | 2 | Reversed-Z depth test (4a: no shim, 4b: ClipControl shim, 4c: depth-flip shim) |
| 5 | 2 | sampler2DArray texture array rendering |
| 6 | 2 | noperspective interpolation qualifier |
| 7 | 2 | FBO blit (glBlitFramebuffer) |
| 8 | 2 | Full RuneLite scene emulation |
| 9 | 2 | Performance baseline (300-frame timing) |

## Results Structure

```
results/run-YYYYMMDD-HHMMSS/
  summary.json          # Module pass/fail + timing
  gl-caps.json          # Full GL capability dump
  function-pointers.json # Function pointer availability
  SUMMARY.md            # Human-readable report
  mesa.log              # Mesa debug output
  libgl-debug.log       # LibGL verbose output
  angle.log             # ANGLE marker log
  mali.log              # Mali driver log
  virgl-server.log      # VirGL server debug
  gallium.xml           # Gallium trace
  tgsi-shaders.log      # TGSI shader dump
  perf-monitor.csv      # CPU/memory/thermal over time
  shaders/              # Captured Mesa shader source
  *.png                 # Framebuffer captures
```

## Important Notes

- **Cannot run concurrently with RuneLite** -- both need the VirGL server and X11 display.
- **Results may contain system-specific info** (GPU model, driver version, env vars). Review before sharing.
- The `LATEST` file in `results/` points to the most recent run directory.
- Disk usage: ~100MB for quick run, ~2GB for full run (Piglit).

## Directory Structure

```
gl-tests/
  scripts/
    deploy.sh           # Push files to device
    install-deps.sh     # Install gcc, GLFW, etc. in proot
    build.sh            # Compile harness + shims
    run-tests.sh        # Master orchestrator
    install-piglit.sh   # Install Piglit (Tier 3)
  src/
    gl_test_harness.c   # Main test harness (9 modules)
    gl_test_log.h       # Logging, JSON output, crash handler
    test_shaders.h      # GLSL shader source strings
    stb_image_write.h   # Vendored PNG writer (public domain)
    fix_inject_clipcontrol.c  # Shim A: inject glClipControl
    fix_flip_depth.c    # Shim B: flip depth range
```

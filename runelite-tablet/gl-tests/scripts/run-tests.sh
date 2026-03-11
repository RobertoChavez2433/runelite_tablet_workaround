#!/usr/bin/env bash
# run-tests.sh — Master orchestrator for VirGL rendering test pipeline.
# Manages VirGL server, X11, debug properties, instrumentation env vars,
# background monitors, test execution, and result collection.
#
# Usage: run-tests.sh [--quick|--full]
#   --quick: Tier 1 + 2 (capability probes, shader tests, shim comparison)
#   --full:  Tier 1 + 2 + 3 (adds Piglit GL 3.3 conformance sweep)

# Self-bootstrap Termux environment
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"

# No -e: proot exit codes are unreliable
set -uo pipefail

# ===== Parse Arguments =====
RUN_MODE="quick"
if [[ "${1:-}" == "--full" ]]; then
    RUN_MODE="full"
elif [[ "${1:-}" == "--quick" ]]; then
    RUN_MODE="quick"
elif [[ -n "${1:-}" ]]; then
    echo "Usage: run-tests.sh [--quick|--full]" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GL_TESTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$GL_TESTS_DIR/src"
BUILD_DIR="$SRC_DIR"
RESULTS_DIR="$GL_TESTS_DIR/results/run-$(date +%Y%m%d-%H%M%S)"

# PID tracking for cleanup
MONITOR_PIDS=()
VIRGL_PID=""

# ===== Saved Debug Properties =====
ORIG_ANGLE_MARKERS=""
ORIG_VULKAN_LAYERS=""

# ===== Cleanup Handler =====
cleanup() {
    echo ""
    echo "[cleanup] Stopping background monitors..."
    for pid in "${MONITOR_PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done

    echo "[cleanup] Stopping VirGL server..."
    if [ -n "$VIRGL_PID" ]; then
        kill "$VIRGL_PID" 2>/dev/null || true
    fi
    # Also kill any stale servers
    pkill -f virgl_test_server 2>/dev/null || true

    echo "[cleanup] Restoring debug properties..."
    setprop debug.angle.markers "$ORIG_ANGLE_MARKERS" 2>/dev/null || true
    setprop debug.vulkan.layers "$ORIG_VULKAN_LAYERS" 2>/dev/null || true

    echo "[cleanup] Done."
}

trap cleanup EXIT INT TERM

# ===== Pre-flight Checks =====
echo "=== VirGL Test Pipeline ($RUN_MODE mode) ==="
echo ""

# Check for VirGL server conflict (RuneLite session running)
if pgrep -f virgl_test_server >/dev/null 2>&1; then
    echo "ERROR: VirGL server already running. Kill RuneLite session first:" >&2
    echo "  pkill -f virgl_test_server" >&2
    exit 1
fi

# Check build complete
if [ ! -f "$GL_TESTS_DIR/.build-complete" ]; then
    echo "ERROR: Test harness not built. Run build.sh first." >&2
    exit 1
fi

# Check binary exists
if [ ! -x "$BUILD_DIR/gl_test_harness" ]; then
    echo "ERROR: gl_test_harness binary not found at $BUILD_DIR/" >&2
    exit 1
fi

# Disk space check (in Termux layer, using df -k)
AVAIL_KB=$(df -k "$HOME" 2>/dev/null | awk 'NR==2{print $4}')
if [ -n "$AVAIL_KB" ]; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    MIN_MB=500
    if [ "$RUN_MODE" = "full" ]; then
        MIN_MB=2048
    fi
    if [ "$AVAIL_MB" -lt "$MIN_MB" ]; then
        echo "WARNING: Low disk space: ${AVAIL_MB}MB available, ${MIN_MB}MB recommended for $RUN_MODE mode" >&2
    fi
    echo "[preflight] Disk space: ${AVAIL_MB}MB available"
fi

# ===== Create Results Directory =====
mkdir -p "$RESULTS_DIR"
mkdir -p "$RESULTS_DIR/shaders"
chmod 700 "$RESULTS_DIR/shaders"
echo "[setup] Results directory: $RESULTS_DIR"

# ===== Save & Set Debug Properties =====
ORIG_ANGLE_MARKERS=$(getprop debug.angle.markers 2>/dev/null || echo "")
ORIG_VULKAN_LAYERS=$(getprop debug.vulkan.layers 2>/dev/null || echo "")

setprop debug.angle.markers 1 2>/dev/null || true
setprop debug.vulkan.layers VK_LAYER_KHRONOS_validation 2>/dev/null || true

# ===== Ensure Termux:X11 is Running =====
echo "[setup] Checking Termux:X11..."
# Kill ALL existing X11 processes to avoid conflicts
pkill -f 'com.termux.x11.Loader' 2>/dev/null || true
pkill -f 'termux-x11' 2>/dev/null || true
sleep 1

# Clean stale X11 state (lock files, sockets from previous runs)
echo "[setup] Cleaning stale X11 state..."
rm -f "$PREFIX/tmp/.tX0-lock" 2>/dev/null || true
rm -f "$PREFIX/tmp/.X11-unix/X0" 2>/dev/null || true

echo "[setup] Starting Termux:X11..."
termux-x11 :0 &
sleep 3
if ! pgrep -f 'com.termux.x11.Loader' >/dev/null 2>&1; then
    echo "ERROR: Termux:X11 failed to start" >&2
    exit 1
fi
# Verify socket was actually created
if [ ! -S "$PREFIX/tmp/.X11-unix/X0" ]; then
    echo "ERROR: X11 socket not created at $PREFIX/tmp/.X11-unix/X0" >&2
    exit 1
fi
echo "[setup] Termux:X11 running"

# ===== Start VirGL Server (tiered: ANGLE first, native GLES fallback) =====
echo "[setup] Starting VirGL server..."

export VIRGL_DEBUG="tgsi,shader,shader_compile,stream,resource,query"
VIRGL_SOCKET="$PREFIX/tmp/.virgl_test"
rm -f "$VIRGL_SOCKET" 2>/dev/null || true

# CRITICAL: Unset LD_LIBRARY_PATH before starting VirGL server.
# Termux binaries have correct rpath baked in. Setting LD_LIBRARY_PATH
# causes system libs (libsqlite.so) to find Termux's OpenSSL 3.x instead
# of the system's, breaking symbol resolution (OpenSSL_add_all_algorithms removed).
echo "[setup] Trying VirGL+ANGLE..."
env -u LD_LIBRARY_PATH virgl_test_server_android --angle-gl >"$RESULTS_DIR/virgl-server.log" 2>&1 &
VIRGL_PID=$!

VIRGL_READY=false
RETRIES=5
while [ $RETRIES -gt 0 ]; do
    sleep 1
    if [ -S "$VIRGL_SOCKET" ]; then
        VIRGL_READY=true
        break
    fi
    if ! kill -0 "$VIRGL_PID" 2>/dev/null; then
        echo "[setup] ANGLE backend failed (server died). Falling back to native GLES..."
        break
    fi
    RETRIES=$((RETRIES - 1))
done

# Tier 2: VirGL + native GLES (fallback)
if [ "$VIRGL_READY" = false ]; then
    kill "$VIRGL_PID" 2>/dev/null || true
    rm -f "$VIRGL_SOCKET" 2>/dev/null || true
    sleep 1

    echo "[setup] Trying VirGL native GLES..."
    env -u LD_LIBRARY_PATH virgl_test_server_android >>"$RESULTS_DIR/virgl-server.log" 2>&1 &
    VIRGL_PID=$!

    RETRIES=10
    while [ ! -S "$VIRGL_SOCKET" ] && [ $RETRIES -gt 0 ]; do
        sleep 1
        if ! kill -0 "$VIRGL_PID" 2>/dev/null; then
            echo "ERROR: VirGL server died (both ANGLE and native). Check $RESULTS_DIR/virgl-server.log" >&2
            cat "$RESULTS_DIR/virgl-server.log" >&2
            exit 1
        fi
        RETRIES=$((RETRIES - 1))
    done
fi

if [ ! -S "$VIRGL_SOCKET" ]; then
    echo "ERROR: VirGL socket not found after 30s at $VIRGL_SOCKET" >&2
    exit 1
fi
echo "[setup] VirGL socket ready: $VIRGL_SOCKET"

# ===== Start Background Monitors =====
echo "[setup] Starting background monitors..."

# Performance monitor: CPU/memory/thermal every 1s
(
    echo "timestamp_ms,cpu_percent,mem_used_kb,thermal_zone0"
    while true; do
        TS=$(date +%s%3N 2>/dev/null || date +%s)
        CPU=$(awk '/^cpu / {u=$2+$4; t=$2+$3+$4+$5+$6+$7+$8; if(t>0) printf "%.1f", u*100/t; else print "0"}' /proc/stat 2>/dev/null || echo "0")
        MEM=$(grep MemAvailable /proc/meminfo 2>/dev/null | awk '{print $2}' || echo "0")
        THERMAL=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo "0")
        echo "$TS,$CPU,$MEM,$THERMAL"
        sleep 1
    done
) >"$RESULTS_DIR/perf-monitor.csv" 2>/dev/null &
MONITOR_PIDS+=($!)

# VirGL watchdog: check server PID every second
(
    while true; do
        if ! kill -0 "$VIRGL_PID" 2>/dev/null; then
            echo "[WATCHDOG] VirGL server died at $(date)" >> "$RESULTS_DIR/virgl-watchdog.log"
            break
        fi
        sleep 1
    done
) &
MONITOR_PIDS+=($!)

# ===== Run Tests Inside proot =====
echo ""
echo "=== Running Tests (mode: $RUN_MODE) ==="
echo ""

# Build the proot command with all instrumentation env vars
PROOT_CMD="proot-distro login ubuntu --shared-tmp -- env"

# CRITICAL: Unset MESA_NO_ERROR (opposite of production — we WANT error checking)
PROOT_CMD="$PROOT_CMD -u MESA_NO_ERROR"

# Instrumentation env vars
PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=virpipe"
PROOT_CMD="$PROOT_CMD VTEST_SOCKET_NAME=/tmp/.virgl_test"
PROOT_CMD="$PROOT_CMD MESA_GLX_ALPHA_BITS=0"
PROOT_CMD="$PROOT_CMD MESA_GL_VERSION_OVERRIDE=4.5COMPAT"
PROOT_CMD="$PROOT_CMD MESA_GLSL_VERSION_OVERRIDE=330"
PROOT_CMD="$PROOT_CMD DISPLAY=:0"
PROOT_CMD="$PROOT_CMD XDG_RUNTIME_DIR=/tmp"
PROOT_CMD="$PROOT_CMD GLFW_PLATFORM=x11"

# Debug/trace env vars
PROOT_CMD="$PROOT_CMD GALLIUM_TRACE=$RESULTS_DIR/gallium.xml"
PROOT_CMD="$PROOT_CMD MESA_LOG_FILE=$RESULTS_DIR/mesa.log"
PROOT_CMD="$PROOT_CMD MESA_LOG_LEVEL=debug"
PROOT_CMD="$PROOT_CMD LIBGL_DEBUG=verbose"
PROOT_CMD="$PROOT_CMD ST_DEBUG=tgsi"
PROOT_CMD="$PROOT_CMD TGSI_PRINT_SANITY=1"
PROOT_CMD="$PROOT_CMD MESA_SHADER_CAPTURE_PATH=$RESULTS_DIR/shaders/"
PROOT_CMD="$PROOT_CMD GALLIUM_PRINT_OPTIONS=1"
PROOT_CMD="$PROOT_CMD GALLIUM_DUMP_CPU=1"

# Capture proot diagnostics
$PROOT_CMD bash -c "
    echo '=== proot diagnostics ===' > \"$RESULTS_DIR/proot-info.txt\"
    uname -a >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    cat /etc/os-release >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    id >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    free -m >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    nproc >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    df -h / >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    df -h /tmp >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    echo 'Socket check:' >> \"$RESULTS_DIR/proot-info.txt\"
    ls -la /tmp/.virgl_test >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
    echo 'X11 check:' >> \"$RESULTS_DIR/proot-info.txt\"
    ls -la /tmp/.X11-unix/ >> \"$RESULTS_DIR/proot-info.txt\" 2>&1
" || true

# Capture X11 info
$PROOT_CMD bash -c "
    xdpyinfo > \"$RESULTS_DIR/xdpyinfo.txt\" 2>&1
" || true

# ===== Tier 1: Capability Probes (Modules 1-3) =====
echo "[tier1] Running capability probes (Modules 1-3)..."
$PROOT_CMD bash -c "
    cd \"$BUILD_DIR\"
    ./gl_test_harness --results-dir \"$RESULTS_DIR\" --all 2>&1
" || echo "[tier1] WARNING: Harness exited non-zero (may be proot noise)"

# ===== Tier 2: Shim Comparison (Module 4 sub-tests) =====
echo ""
echo "[tier2] Running shim comparison tests..."

echo "[tier2] Module 4a: No shim (baseline)..."
$PROOT_CMD bash -c "
    cd \"$BUILD_DIR\"
    ./gl_test_harness --results-dir \"$RESULTS_DIR\" --module 4a 2>&1
" || echo "[tier2] WARNING: Module 4a exited non-zero"

echo "[tier2] Module 4b: ClipControl shim (Shim A)..."
$PROOT_CMD bash -c "
    cd \"$BUILD_DIR\"
    LD_PRELOAD=\"$BUILD_DIR/fix_inject_clipcontrol.so\" \
        ./gl_test_harness --results-dir \"$RESULTS_DIR\" --module 4b 2>&1
" || echo "[tier2] WARNING: Module 4b exited non-zero"

echo "[tier2] Module 4c: Depth flip shim (Shim B)..."
$PROOT_CMD bash -c "
    cd \"$BUILD_DIR\"
    LD_PRELOAD=\"$BUILD_DIR/fix_flip_depth.so\" \
        ./gl_test_harness --results-dir \"$RESULTS_DIR\" --module 4c 2>&1
" || echo "[tier2] WARNING: Module 4c exited non-zero"

# ===== Tier 3: Piglit (optional, --full only) =====
if [ "$RUN_MODE" = "full" ]; then
    echo ""
    echo "[tier3] Running Piglit GL 3.3 conformance sweep..."

    PIGLIT_DIR="$HOME/piglit-src"
    if [ ! -d "$PIGLIT_DIR" ]; then
        echo "[tier3] ERROR: Piglit not installed. Run install-piglit.sh first." >&2
    else
        mkdir -p "$RESULTS_DIR/piglit"
        $PROOT_CMD bash -c "
            cd \"$PIGLIT_DIR\"
            python3 piglit run \
                --backend json \
                -t 'spec@!opengl 3.3' \
                -x 'spec@!opengl 3.3@gl-3.2' \
                gpu \
                \"$RESULTS_DIR/piglit\" \
                2>&1
        " || echo "[tier3] WARNING: Piglit exited non-zero"

        # Generate Piglit summary
        $PROOT_CMD bash -c "
            cd \"$PIGLIT_DIR\"
            python3 piglit summary console \"$RESULTS_DIR/piglit\" \
                > \"$RESULTS_DIR/piglit-summary.txt\" 2>&1
        " || true
    fi
fi

# ===== Collect Results =====
echo ""
echo "[results] Collecting results..."

# Copy TGSI shader logs (may be in stderr redirects)
# Redirect stderr from libgl-debug
if [ -f "$RESULTS_DIR/mesa.log" ]; then
    echo "[results] Mesa log: $(wc -l < "$RESULTS_DIR/mesa.log") lines"
fi

# Generate SUMMARY.md from summary.json
if [ -f "$RESULTS_DIR/summary.json" ]; then
    echo "[results] Generating SUMMARY.md..."
    $PROOT_CMD bash -c "
        python3 -c \"
import json, sys
with open('$RESULTS_DIR/summary.json') as f:
    data = json.load(f)
print('# VirGL Test Results')
print()
print('Started:', data.get('started', 'unknown'))
print()
print('| Module | Name | Status | Time (ms) | Detail |')
print('|--------|------|--------|-----------|--------|')
for m in data.get('modules', []):
    sub = m.get('sub', '')
    detail = m.get('detail', '')
    print(f'| {m[\"module\"]}{sub} | {m[\"name\"]} | {m[\"status\"]} | {m[\"time_ms\"]:.1f} | {detail} |')
if data.get('crashed'):
    print()
    print(f'**CRASHED**: {data[\"crashed\"]}')
\" > \"$RESULTS_DIR/SUMMARY.md\" 2>&1
    " || echo "[results] WARNING: SUMMARY.md generation failed"
fi

# ===== Package Results =====
echo "[results] Packaging results..."

# Create tarball in Termux shared-tmp for easy retrieval
TAR_PATH="$PREFIX/tmp/gl-results-latest.tar.gz"
tar czf "$TAR_PATH" -C "$RESULTS_DIR" . 2>/dev/null || true

# Write LATEST pointer (not symlink per review feedback)
echo "$RESULTS_DIR" > "$GL_TESTS_DIR/results/LATEST"

echo ""
echo "=== Test Pipeline Complete ==="
echo "Results: $RESULTS_DIR"
echo "Tarball: $TAR_PATH"
echo ""
echo "Pull results with:"
echo "  adb shell \"run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc 'cat \$PREFIX/tmp/gl-results-latest.tar.gz'\" > gl-results.tar.gz"

# Print quick summary if available
if [ -f "$RESULTS_DIR/SUMMARY.md" ]; then
    echo ""
    cat "$RESULTS_DIR/SUMMARY.md"
fi

#!/usr/bin/env bash
# frame-pacing.sh — Standalone Termux:X11 + VirGL pacing probe outside RuneLite.
# Usage: frame-pacing.sh [duration_seconds]

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"

set -uo pipefail

DURATION_SECONDS="${1:-20}"
X11_EXTRA_ARGS="${TERMUX_X11_ARGS:-}"
GLXGEARS_ARGS="${GLXGEARS_ARGS:--fullscreen -info}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GL_TESTS_DIR="${GL_TESTS_BASE:-$(cd "$SCRIPT_DIR/.." && pwd)}"
RESULTS_DIR="$GL_TESTS_DIR/results/frame-pacing-$(date +%Y%m%d-%H%M%S)"
PROOT_RESULTS_DIR="/tmp/frame-pacing-results"
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
VIRGL_SOCKET="$PREFIX/tmp/.virgl_test"
X11_PID=""
VIRGL_PID=""

cleanup() {
    [ -n "$VIRGL_PID" ] && kill "$VIRGL_PID" 2>/dev/null || true
    [ -n "$X11_PID" ] && kill "$X11_PID" 2>/dev/null || true
    pkill -f virgl_test_server 2>/dev/null || true
    pkill -f 'com.termux.x11.Loader' 2>/dev/null || true
    pkill -f 'termux-x11' 2>/dev/null || true
}

trap cleanup EXIT INT TERM

mkdir -p "$RESULTS_DIR"
echo "=== Frame pacing probe $(date) ===" | tee "$RESULTS_DIR/SUMMARY.txt"
echo "Results: $RESULTS_DIR" | tee -a "$RESULTS_DIR/SUMMARY.txt"
echo "Termux:X11 extra args: ${X11_EXTRA_ARGS:-<none>}" | tee -a "$RESULTS_DIR/SUMMARY.txt"
echo "glxgears args: ${GLXGEARS_ARGS}" | tee -a "$RESULTS_DIR/SUMMARY.txt"

if pgrep -f 'net.runelite.client.RuneLite' >/dev/null 2>&1; then
    echo "ERROR: RuneLite is still running. Stop it before running frame-pacing.sh." | tee -a "$RESULTS_DIR/SUMMARY.txt" >&2
    exit 1
fi

echo "[setup] Cleaning old X11/VirGL state..." | tee -a "$RESULTS_DIR/SUMMARY.txt"
pkill -f virgl_test_server 2>/dev/null || true
pkill -f 'com.termux.x11.Loader' 2>/dev/null || true
pkill -f 'termux-x11' 2>/dev/null || true
rm -f "$TMPDIR/.tX0-lock" "$TMPDIR/.X0-lock" "$X11_SOCKET_DIR/X0" "$VIRGL_SOCKET" 2>/dev/null || true
mkdir -p "$X11_SOCKET_DIR"
sleep 1

echo "[setup] Starting Termux:X11..." | tee -a "$RESULTS_DIR/SUMMARY.txt"
/data/data/com.termux/files/usr/bin/termux-x11 :0 $X11_EXTRA_ARGS >"$RESULTS_DIR/termux-x11.log" 2>&1 &
X11_PID=$!
for _ in $(seq 1 60); do
    [ -S "$X11_SOCKET_DIR/X0" ] && break
    sleep 0.2
done
if [ ! -S "$X11_SOCKET_DIR/X0" ]; then
    echo "ERROR: X11 socket not ready" | tee -a "$RESULTS_DIR/SUMMARY.txt" >&2
    exit 1
fi

am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
echo "[setup] X11 ready" | tee -a "$RESULTS_DIR/SUMMARY.txt"

echo "[setup] Starting native VirGL..." | tee -a "$RESULTS_DIR/SUMMARY.txt"
env -u LD_LIBRARY_PATH virgl_test_server_android >"$RESULTS_DIR/virgl-server.log" 2>&1 &
VIRGL_PID=$!
for _ in $(seq 1 50); do
    if kill -0 "$VIRGL_PID" 2>/dev/null && [ -S "$VIRGL_SOCKET" ]; then
        break
    fi
    sleep 0.2
done
if [ ! -S "$VIRGL_SOCKET" ]; then
    echo "ERROR: VirGL socket not ready" | tee -a "$RESULTS_DIR/SUMMARY.txt" >&2
    exit 1
fi
echo "[setup] VirGL ready" | tee -a "$RESULTS_DIR/SUMMARY.txt"

PROOT_ENV=(
    DISPLAY=:0
    XDG_RUNTIME_DIR=/tmp
    GLFW_PLATFORM=x11
    GALLIUM_DRIVER=virpipe
    VTEST_SOCKET_NAME=/tmp/.virgl_test
    MESA_GLX_ALPHA_BITS=0
    MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
    MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    MESA_GLSL_VERSION_OVERRIDE=430
)

echo "[probe] Running display and FPS checks for ${DURATION_SECONDS}s..." | tee -a "$RESULTS_DIR/SUMMARY.txt"
proot-distro login ubuntu --shared-tmp -- env "${PROOT_ENV[@]}" bash -lc "
    set -uo pipefail
    rm -rf '$PROOT_RESULTS_DIR'
    mkdir -p '$PROOT_RESULTS_DIR'
    echo '=== xdpyinfo ===' > '$PROOT_RESULTS_DIR/xdpyinfo.log'
    xdpyinfo >> '$PROOT_RESULTS_DIR/xdpyinfo.log' 2>&1 || true
    echo '=== xrandr ===' > '$PROOT_RESULTS_DIR/xrandr.log'
    xrandr >> '$PROOT_RESULTS_DIR/xrandr.log' 2>&1 || true
    echo '=== glxinfo ===' > '$PROOT_RESULTS_DIR/glxinfo.log'
    glxinfo 2>&1 | grep -iE '(renderer|version|vendor|direct)' >> '$PROOT_RESULTS_DIR/glxinfo.log' || true
    timeout '$DURATION_SECONDS' glxgears ${GLXGEARS_ARGS} > '$PROOT_RESULTS_DIR/glxgears.log' 2>&1 || true
"

cp "$TMPDIR/frame-pacing-results/"* "$RESULTS_DIR/" 2>/dev/null || true

{
    echo ""
    echo "=== Renderer ==="
    grep -i 'renderer' "$RESULTS_DIR/glxinfo.log" || true
    echo ""
    echo "=== Display ==="
    grep -E ' connected|\\*' "$RESULTS_DIR/xrandr.log" || true
    echo ""
    echo "=== glxgears FPS ==="
    grep -E '^[0-9]+ frames in' "$RESULTS_DIR/glxgears.log" || echo "No FPS lines captured"
} | tee -a "$RESULTS_DIR/SUMMARY.txt"

echo "[probe] Frame pacing probe complete" | tee -a "$RESULTS_DIR/SUMMARY.txt"

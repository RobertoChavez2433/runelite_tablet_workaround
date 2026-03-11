#!/usr/bin/env bash
# Quick diagnostic: test X11, VirGL, and GL from proot
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"

set -uo pipefail

echo "=== X11 Diagnostics ==="

# Check X server process
echo "[1] X11 process check:"
pgrep -af 'com.termux.x11' 2>&1 || echo "  X11 NOT RUNNING"

# Check X11 socket
echo "[2] X11 socket:"
ls -la "$PREFIX/tmp/.X11-unix/" 2>&1 || echo "  NOT FOUND"

# Check lock file
echo "[3] X11 lock file:"
ls -la "$PREFIX/tmp/.tX0-lock" 2>&1 || echo "  No lock file"

# Clean stale X11 state and restart
echo "[4] Cleaning stale X11 state..."
rm -f "$PREFIX/tmp/.tX0-lock" 2>/dev/null || true
rm -f "$PREFIX/tmp/.X11-unix/X0" 2>/dev/null || true
pkill -f 'com.termux.x11.Loader' 2>/dev/null || true
pkill -f 'termux-x11' 2>/dev/null || true
sleep 1

echo "[5] Starting fresh X11..."
termux-x11 :0 &
sleep 3

echo "[6] X11 process after restart:"
pgrep -af 'com.termux.x11' 2>&1 || echo "  STILL NOT RUNNING"

echo "[7] X11 socket after restart:"
ls -la "$PREFIX/tmp/.X11-unix/" 2>&1 || echo "  NOT FOUND"

# Check VirGL
echo "[8] VirGL socket:"
ls -la "$PREFIX/tmp/.virgl_test" 2>&1 || echo "  NOT FOUND"

echo "[9] Starting VirGL server..."
pkill -f virgl_test_server 2>/dev/null || true
sleep 1
virgl_test_server_android --angle-gl >/dev/null 2>&1 &
VPID=$!
RETRIES=10
while [ ! -S "$PREFIX/tmp/.virgl_test" ] && [ $RETRIES -gt 0 ]; do
    sleep 1
    RETRIES=$((RETRIES - 1))
done
echo "[10] VirGL socket status:"
ls -la "$PREFIX/tmp/.virgl_test" 2>&1 || echo "  FAILED"

# Test X11 from proot
echo "[11] proot X11 test (xdpyinfo):"
proot-distro login ubuntu --shared-tmp -- env DISPLAY=:0 bash -c 'xdpyinfo 2>&1 | head -5' || echo "  FAILED"

# Test glxgears from proot
echo "[12] proot glxgears (virpipe):"
proot-distro login ubuntu --shared-tmp -- env \
    DISPLAY=:0 \
    XDG_RUNTIME_DIR=/tmp \
    GALLIUM_DRIVER=virpipe \
    VTEST_SOCKET_NAME=/tmp/.virgl_test \
    MESA_GLX_ALPHA_BITS=0 \
    MESA_GL_VERSION_OVERRIDE=4.5COMPAT \
    MESA_GLSL_VERSION_OVERRIDE=330 \
    bash -c 'timeout 3 glxgears -info 2>&1 | head -10' || echo "  GLXGEARS FAILED"

# Test harness from proot
echo "[13] proot gl_test_harness Module 1:"
proot-distro login ubuntu --shared-tmp -- env \
    DISPLAY=:0 \
    XDG_RUNTIME_DIR=/tmp \
    GLFW_PLATFORM=x11 \
    GALLIUM_DRIVER=virpipe \
    VTEST_SOCKET_NAME=/tmp/.virgl_test \
    MESA_GLX_ALPHA_BITS=0 \
    MESA_GL_VERSION_OVERRIDE=4.5COMPAT \
    MESA_GLSL_VERSION_OVERRIDE=330 \
    LIBGL_DEBUG=verbose \
    bash -c 'mkdir -p /tmp/diag && /data/data/com.termux/files/home/gl-tests/src/gl_test_harness --results-dir /tmp/diag --module 1 2>&1 | head -30' || echo "  HARNESS FAILED"

# Cleanup
echo "[14] Cleaning up..."
pkill -f virgl_test_server 2>/dev/null || true

echo "=== Done ==="

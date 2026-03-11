#!/usr/bin/env bash
# build.sh — Compile gl_test_harness + shims inside proot Ubuntu.
# Idempotent: skips if marker exists and binaries exist.
# Runs inside Termux (not proot) — self-bootstraps Termux PATH.

# Self-bootstrap Termux environment
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"

# No -e: proot exit codes are unreliable
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GL_TESTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$GL_TESTS_DIR/src"
MARKER_FILE="$GL_TESTS_DIR/.build-complete"

# Check deps installed
if [ ! -f "$GL_TESTS_DIR/.deps-installed" ]; then
    echo "[build] ERROR: Dependencies not installed. Run install-deps.sh first." >&2
    exit 1
fi

# Check if already built
if [ -f "$MARKER_FILE" ]; then
    echo "[build] Marker found. Verifying binaries..."
    BINARIES_OK=true
    proot-distro login ubuntu --shared-tmp -- bash -c "
        [ -x \"$SRC_DIR/gl_test_harness\" ] && \
        [ -f \"$SRC_DIR/fix_inject_clipcontrol.so\" ] && \
        [ -f \"$SRC_DIR/fix_flip_depth.so\" ]
    " || BINARIES_OK=false

    if [ "$BINARIES_OK" = true ]; then
        echo "[build] All binaries verified. Skipping build."
        exit 0
    else
        echo "[build] Marker exists but binaries missing. Rebuilding..."
        rm -f "$MARKER_FILE"
    fi
fi

echo "[build] Compiling test harness and shims..."

proot-distro login ubuntu --shared-tmp -- bash -c "
    set -uo pipefail
    cd \"$SRC_DIR\"

    echo '[build] Compiling gl_test_harness...'
    gcc -o gl_test_harness gl_test_harness.c \
        -lGL -lGLU -lglfw -lX11 -lm -ldl \
        -DSTB_IMAGE_WRITE_IMPLEMENTATION \
        -Wall -Wextra -Wno-unused-parameter \
        2>&1
    echo \"[build] gl_test_harness: exit=\$?\"

    echo '[build] Compiling fix_inject_clipcontrol.so...'
    gcc -shared -fPIC -o fix_inject_clipcontrol.so fix_inject_clipcontrol.c -ldl \
        -Wall -Wextra \
        2>&1
    echo \"[build] fix_inject_clipcontrol.so: exit=\$?\"

    echo '[build] Compiling fix_flip_depth.so...'
    gcc -shared -fPIC -o fix_flip_depth.so fix_flip_depth.c -ldl \
        -Wall -Wextra \
        2>&1
    echo \"[build] fix_flip_depth.so: exit=\$?\"

    echo '[build] Setting permissions...'
    chmod 755 gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so

    echo '[build] Verifying...'
    ls -la gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so
"

# Verify binaries were created (don't trust proot exit code)
VERIFY_OK=true
proot-distro login ubuntu --shared-tmp -- bash -c "
    [ -x \"$SRC_DIR/gl_test_harness\" ] && \
    [ -f \"$SRC_DIR/fix_inject_clipcontrol.so\" ] && \
    [ -f \"$SRC_DIR/fix_flip_depth.so\" ]
" || VERIFY_OK=false

if [ "$VERIFY_OK" = true ]; then
    echo "[build] Build successful."
    touch "$MARKER_FILE"
    echo "[build] Marker written: $MARKER_FILE"
else
    echo "[build] ERROR: Build verification failed." >&2
    exit 1
fi

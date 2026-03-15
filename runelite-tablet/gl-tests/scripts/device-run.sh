#!/bin/bash
# device-run.sh — Self-contained build+run script for gl-tests.
# Push to /data/local/tmp/ and run via:
#   adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash /data/local/tmp/device-run.sh"

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"

GL_TESTS="$HOME/gl-tests"
SRC="$GL_TESTS/src"

echo "=== device-run.sh ==="
echo "HOME=$HOME"
echo "GL_TESTS=$GL_TESTS"
echo "SRC=$SRC"

# Verify source files exist
if [ ! -f "$SRC/gl_test_harness.c" ]; then
    echo "ERROR: Source files not found at $SRC"
    ls -la "$SRC/" 2>&1 || echo "(directory does not exist)"
    exit 1
fi

# Build in proot
echo ""
echo "=== Building ==="
rm -rf "$TMPDIR/gl-src"
cp -r "$SRC" "$TMPDIR/gl-src"

proot-distro login ubuntu --shared-tmp -- bash -c '
    set -uo pipefail
    cd /tmp/gl-src
    echo "Building gl_test_harness..."
    gcc -o gl_test_harness gl_test_harness.c \
        -lGL -lGLU -lglfw -lX11 -lm -ldl \
        -DSTB_IMAGE_WRITE_IMPLEMENTATION \
        -Wall -Wextra -Wno-unused-parameter 2>&1
    echo "Building fix_inject_clipcontrol.so..."
    gcc -shared -fPIC -o fix_inject_clipcontrol.so fix_inject_clipcontrol.c -ldl -Wall -Wextra 2>&1
    echo "Building fix_flip_depth.so..."
    gcc -shared -fPIC -o fix_flip_depth.so fix_flip_depth.c -ldl -Wall -Wextra 2>&1
    chmod 755 gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so
    ls -la gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so
'

# Copy binaries back
cp "$TMPDIR/gl-src/gl_test_harness" "$SRC/"
cp "$TMPDIR/gl-src/fix_inject_clipcontrol.so" "$SRC/"
cp "$TMPDIR/gl-src/fix_flip_depth.so" "$SRC/"
chmod 755 "$SRC/gl_test_harness" "$SRC/fix_inject_clipcontrol.so" "$SRC/fix_flip_depth.so"

# Create markers
touch "$GL_TESTS/.deps-installed" "$GL_TESTS/.build-complete"

# Verify binary
if [ ! -x "$SRC/gl_test_harness" ]; then
    echo "ERROR: Build failed - binary not found"
    exit 1
fi
echo "Build OK: $(ls -la "$SRC/gl_test_harness")"

# Run tests
echo ""
echo "=== Running tests (--quick) ==="
source "$GL_TESTS/scripts/run-tests.sh" --quick

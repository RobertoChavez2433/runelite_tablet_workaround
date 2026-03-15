#!/usr/bin/env bash
# build-via-tmp.sh — Build gl-tests from /tmp (proot-accessible).
# Run this via: source ~/gl-tests/scripts/build-via-tmp.sh

export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"

set -uo pipefail

SRC_DIR="$HOME/gl-tests/src"

# Copy sources to /tmp for proot access
rm -rf "$PREFIX/tmp/gl-src"
cp -r "$SRC_DIR" "$PREFIX/tmp/gl-src"

echo "[build] Compiling in proot from /tmp/gl-src..."

proot-distro login ubuntu --shared-tmp -- bash -c "
    set -uo pipefail
    cd /tmp/gl-src

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

    chmod 755 gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so
    ls -la gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so
"

# Copy binaries back to source dir
echo "[build] Copying binaries back..."
cp "$PREFIX/tmp/gl-src/gl_test_harness" "$SRC_DIR/"
cp "$PREFIX/tmp/gl-src/fix_inject_clipcontrol.so" "$SRC_DIR/"
cp "$PREFIX/tmp/gl-src/fix_flip_depth.so" "$SRC_DIR/"
chmod 755 "$SRC_DIR/gl_test_harness" "$SRC_DIR/fix_inject_clipcontrol.so" "$SRC_DIR/fix_flip_depth.so"

# Verify
if [ -x "$SRC_DIR/gl_test_harness" ] && [ -f "$SRC_DIR/fix_inject_clipcontrol.so" ] && [ -f "$SRC_DIR/fix_flip_depth.so" ]; then
    echo "[build] Build successful."
    touch "$HOME/gl-tests/.build-complete"
else
    echo "[build] ERROR: Build verification failed." >&2
    exit 1
fi

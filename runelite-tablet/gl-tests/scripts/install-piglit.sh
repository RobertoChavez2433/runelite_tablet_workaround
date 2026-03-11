#!/usr/bin/env bash
# install-piglit.sh — Build Piglit from source inside proot Ubuntu for GL 3.3 conformance testing.
# Idempotent: skips if marker file exists and piglit binary exists.
# Runs inside Termux (not proot) — self-bootstraps Termux PATH.
#
# Requires: install-deps.sh already run (gcc available in proot)
# Disk requirement: ~2GB free inside proot rootfs

# Self-bootstrap Termux environment
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"

# No -e: proot exit codes are unreliable (/proc/self/fd warnings = non-zero on success)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GL_TESTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MARKER_FILE="$GL_TESTS_DIR/.piglit-installed"
PIGLIT_SRC_DIR="$HOME/piglit-src"

# Check if already installed
if [ -f "$MARKER_FILE" ]; then
    echo "[install-piglit] Marker found. Verifying installation..."
    VERIFY_OK=true
    proot-distro login ubuntu --shared-tmp -- bash -c "
        [ -x \"$PIGLIT_SRC_DIR/build/piglit\" ] || [ -x \"$PIGLIT_SRC_DIR/piglit\" ]
    " || VERIFY_OK=false

    if [ "$VERIFY_OK" = true ]; then
        echo "[install-piglit] Piglit verified. Skipping install."
        exit 0
    else
        echo "[install-piglit] Marker exists but piglit binary missing. Rebuilding..."
        rm -f "$MARKER_FILE"
    fi
fi

# Disk space check (need ~2GB free inside proot rootfs)
echo "[install-piglit] Checking disk space..."
AVAIL_KB=""
AVAIL_KB=$(proot-distro login ubuntu --shared-tmp -- bash -c "df -k / 2>/dev/null | awk 'NR==2{print \$4}'" 2>/dev/null) || true

if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -gt 0 ] 2>/dev/null; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    if [ "$AVAIL_MB" -lt 2048 ]; then
        echo "[install-piglit] ERROR: Insufficient disk space: ${AVAIL_MB}MB available, 2048MB required." >&2
        exit 1
    fi
    echo "[install-piglit] Disk space OK: ${AVAIL_MB}MB available"
else
    echo "[install-piglit] WARNING: Could not determine disk space. Proceeding anyway."
fi

# Check base deps installed
if [ ! -f "$GL_TESTS_DIR/.deps-installed" ]; then
    echo "[install-piglit] ERROR: Base dependencies not installed. Run install-deps.sh first." >&2
    exit 1
fi

echo "[install-piglit] Installing Piglit build dependencies..."

proot-distro login ubuntu --shared-tmp -- env DEBIAN_FRONTEND=noninteractive bash -c '
    echo "[install-piglit] Running apt-get update..."
    apt-get update -qq || true

    echo "[install-piglit] Installing Piglit build deps..."
    apt-get install -y -qq \
        cmake \
        python3-numpy \
        libwaffle-dev \
        freeglut3-dev \
        python3-mako \
        git \
        2>&1

    echo "[install-piglit] Verifying build deps..."
    which cmake && cmake --version | head -1
    which python3 && python3 --version
    which git && git --version
'

echo "[install-piglit] Cloning and building Piglit..."

proot-distro login ubuntu --shared-tmp -- bash -c "
    set -uo pipefail

    PIGLIT_SRC=\"$PIGLIT_SRC_DIR\"

    # Clone if not already present
    if [ ! -d \"\$PIGLIT_SRC\" ]; then
        echo '[install-piglit] Cloning Piglit (shallow)...'
        git clone --depth 1 https://gitlab.freedesktop.org/mesa/piglit.git \"\$PIGLIT_SRC\" 2>&1
    else
        echo '[install-piglit] Piglit source already present, skipping clone.'
    fi

    echo '[install-piglit] Running cmake...'
    cd \"\$PIGLIT_SRC\"
    cmake -B build \
        -DPIGLIT_BUILD_DMA_BUF_TESTS=OFF \
        -DPIGLIT_BUILD_CL_TESTS=OFF \
        2>&1

    echo '[install-piglit] Building...'
    cmake --build build -j\$(nproc) 2>&1

    echo '[install-piglit] Build complete.'
    ls -la build/piglit 2>/dev/null || ls -la piglit 2>/dev/null || echo 'WARNING: piglit binary not found at expected location'
"

# Verify piglit was built (don't trust proot exit code)
VERIFY_OK=true
proot-distro login ubuntu --shared-tmp -- bash -c "
    [ -x \"$PIGLIT_SRC_DIR/build/piglit\" ] || [ -x \"$PIGLIT_SRC_DIR/piglit\" ]
" || VERIFY_OK=false

if [ "$VERIFY_OK" = true ]; then
    echo "[install-piglit] Piglit installed successfully."
    touch "$MARKER_FILE"
    echo "[install-piglit] Marker written: $MARKER_FILE"
else
    echo "[install-piglit] ERROR: Piglit build verification failed." >&2
    echo "[install-piglit] Check if the build completed without errors." >&2
    exit 1
fi

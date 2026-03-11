#!/usr/bin/env bash
# install-deps.sh — Install build dependencies (gcc, GLFW, etc.) inside proot Ubuntu.
# Idempotent: skips if marker file exists and deps verify.
# Runs inside Termux (not proot) — self-bootstraps Termux PATH.

# Self-bootstrap Termux environment
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"

# No -e: proot exit codes are unreliable (/proc/self/fd warnings = non-zero on success)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GL_TESTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MARKER_FILE="$GL_TESTS_DIR/.deps-installed"

# Check if already installed
if [ -f "$MARKER_FILE" ]; then
    echo "[install-deps] Marker found. Verifying deps..."
    VERIFY_OK=true
    proot-distro login ubuntu --shared-tmp -- bash -c '
        which gcc >/dev/null 2>&1 && \
        which g++ >/dev/null 2>&1 && \
        which pkg-config >/dev/null 2>&1 && \
        dpkg -l libglfw3-dev >/dev/null 2>&1
    ' || VERIFY_OK=false

    if [ "$VERIFY_OK" = true ]; then
        echo "[install-deps] All deps verified. Skipping install."
        exit 0
    else
        echo "[install-deps] Marker exists but deps missing. Re-installing..."
        rm -f "$MARKER_FILE"
    fi
fi

echo "[install-deps] Installing build dependencies in proot Ubuntu..."

proot-distro login ubuntu --shared-tmp -- env DEBIAN_FRONTEND=noninteractive bash -c '
    echo "[install-deps] Running apt-get update..."
    apt-get update -qq || true

    echo "[install-deps] Installing packages..."
    apt-get install -y -qq \
        gcc \
        g++ \
        libglfw3-dev \
        libgl-dev \
        libglu1-mesa-dev \
        pkg-config \
        2>&1

    echo "[install-deps] Verifying installation..."
    which gcc && gcc --version | head -1
    which g++ && g++ --version | head -1
    which pkg-config && pkg-config --version
    dpkg -l libglfw3-dev | grep -E "^ii"
'

INSTALL_STATUS=$?

# Verify with which (don't trust exit code)
VERIFY_OK=true
proot-distro login ubuntu --shared-tmp -- bash -c '
    which gcc >/dev/null 2>&1 && \
    which g++ >/dev/null 2>&1 && \
    which pkg-config >/dev/null 2>&1 && \
    dpkg -l libglfw3-dev >/dev/null 2>&1
' || VERIFY_OK=false

if [ "$VERIFY_OK" = true ]; then
    echo "[install-deps] All dependencies installed successfully."
    touch "$MARKER_FILE"
    echo "[install-deps] Marker written: $MARKER_FILE"
else
    echo "[install-deps] ERROR: Dependency verification failed." >&2
    echo "[install-deps] proot exit code was: $INSTALL_STATUS" >&2
    exit 1
fi

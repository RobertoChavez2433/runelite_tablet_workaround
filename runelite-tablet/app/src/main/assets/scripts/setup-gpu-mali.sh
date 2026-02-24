#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

echo "=== Mali GPU setup $(date) ==="

# Install virglrenderer and ANGLE in Termux (these run OUTSIDE proot)
echo "Installing virglrenderer-android and angle-android..."
pkg install -y virglrenderer-android angle-android 2>&1 || {
    echo "WARNING: Failed to install VirGL/ANGLE packages"
}

# Verify host-side VirGL server is available
if ! command -v virgl_test_server_android >/dev/null 2>&1; then
    echo "WARNING: virgl_test_server_android not found after install"
    echo "GPU acceleration will not be available — software rendering only"
    echo "GPU_SETUP_COMPLETE"
    exit 0
fi

# Install Mesa inside proot (provides virpipe gallium driver)
proot-distro login ubuntu -- bash -c '
    set -euo pipefail

    GPU_MARKER="/root/.rlt-gpu-installed"

    if [ -f "$GPU_MARKER" ]; then
        echo "GPU marker found — verifying libraries..."
        if command -v glxinfo >/dev/null 2>&1 && \
           [ -f "/usr/lib/aarch64-linux-gnu/libGL.so.1" -o -f "/usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so" ]; then
            echo "Mesa libraries verified"
            echo "GPU_SETUP_COMPLETE"
            exit 0
        fi
        echo "GPU marker exists but libraries missing — reinstalling"
    fi

    # Disk space pre-check (512MB minimum)
    AVAILABLE_KB=$(df -k / 2>/dev/null | tail -1 | awk "{print \$4}")
    if [ "${AVAILABLE_KB:-0}" -lt 524288 ]; then
        echo "ERROR: insufficient disk space (${AVAILABLE_KB:-unknown}KB available, 512MB required)"
        echo "GPU driver installation skipped"
        echo "GPU_SETUP_COMPLETE"
        exit 0
    fi

    echo "Installing Mesa (virpipe driver) in proot..."

    # Install X11/GL dependencies (NOT Mesa itself — we install lfdevs Mesa below)
    apt-get update -qq
    apt-get install -y -qq mesa-utils libx11-6 libxext6 libxfixes3 \
        libxshmfence1 libxxf86vm1 libdrm2 libexpat1 libwayland-client0 \
        libelf1 zlib1g libzstd1 wget >/dev/null

    # Download lfdevs Mesa (includes virgl Gallium driver for virpipe)
    MESA_VERSION="26.1.0"
    MESA_BASE="https://github.com/lfdevs/mesa-for-android-container/releases/download"
    MESA_TAG="mesa-${MESA_VERSION}"
    cd /tmp
    echo "Downloading Mesa ${MESA_VERSION} (with virgl driver)..."
    wget -q -O mesa.tar.gz "${MESA_BASE}/${MESA_TAG}/mesa-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"
    echo "Installing Mesa..."
    tar -zxf mesa.tar.gz -C /
    rm -f /tmp/mesa.tar.gz
    ldconfig

    echo "${MESA_VERSION}" > "$GPU_MARKER"
    echo "GPU_SETUP_COMPLETE"
'

echo "=== setup-gpu-mali complete ==="

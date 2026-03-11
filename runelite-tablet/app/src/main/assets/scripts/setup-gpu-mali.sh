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

# Install/repair Mesa inside proot (provides virpipe gallium driver)
# IMPORTANT: Use STOCK Ubuntu Mesa, NOT lfdevs Mesa.
# lfdevs Mesa is built for Adreno/Turnip and causes BadMatch errors with virpipe
# on Termux:X11 (32-bit vs 24-bit visual depth mismatch).
# All community VirGL+proot setups (sabamdarif, LinuxDroidMaster, Ivon) use stock Mesa.
proot-distro login ubuntu -- bash -c '
    set -euo pipefail

    GPU_MARKER="/root/.rlt-gpu-installed"

    echo "Installing/repairing stock Mesa (virpipe driver) in proot..."

    apt-get update -qq

    # Reinstall stock Mesa packages to repair any lfdevs overwrite from previous versions.
    # Stock Ubuntu Mesa includes virpipe via virtio_gpu_dri.so in libgl1-mesa-dri.
    apt-get install -y --reinstall -qq \
        mesa-utils libgl1-mesa-dri mesa-libgallium libegl-mesa0 libglx-mesa0 \
        libx11-6 libxext6 libxfixes3 libxshmfence1 libxxf86vm1 \
        libdrm2 libexpat1 libwayland-client0 libelf1 zlib1g libzstd1 >/dev/null
    ldconfig

    # Remove stale lfdevs libgallium if present (stock reinstall restores the correct one)
    rm -f /usr/lib/aarch64-linux-gnu/libgallium-*-devel.so 2>/dev/null || true

    echo "stock-mesa" > "$GPU_MARKER"
    echo "GPU_SETUP_COMPLETE"
'

echo "=== setup-gpu-mali complete ==="

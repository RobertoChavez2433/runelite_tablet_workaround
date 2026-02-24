#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

echo "=== GPU driver setup $(date) ==="

proot-distro login ubuntu -- bash -c '
    set -euo pipefail

    GPU_MARKER="/root/.rlt-gpu-installed"

    # Idempotent — skip if already installed
    if [ -f "$GPU_MARKER" ]; then
        echo "GPU drivers already installed"
        if [ -f "/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so" ] || \
           [ -f "/usr/lib/aarch64-linux-gnu/libEGL_mesa.so" ]; then
            echo "GPU_SETUP_COMPLETE"
            exit 0
        fi
        echo "GPU marker exists but libraries missing — reinstalling"
    fi

    echo "Installing Mesa + Turnip GPU drivers..."

    apt-get update -qq
    apt-get install -y -qq wget libx11-6 libxext6 libxfixes3 libxshmfence1 \
        libxxf86vm1 libdrm2 libexpat1 libwayland-client0 libelf1 \
        zlib1g libzstd1 > /dev/null 2>&1

    MESA_VERSION="26.1.0"
    MESA_BASE="https://github.com/lfdevs/mesa-for-android-container/releases/download"
    MESA_TAG="mesa-${MESA_VERSION}"

    cd /tmp

    echo "Downloading Mesa ${MESA_VERSION}..."
    wget -q -O mesa.tar.gz "${MESA_BASE}/${MESA_TAG}/mesa-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"

    echo "Downloading Turnip driver..."
    wget -q -O turnip.tar.gz "${MESA_BASE}/${MESA_TAG}/turnip-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"

    echo "Installing Mesa..."
    tar -zxf mesa.tar.gz -C /

    echo "Installing Turnip..."
    tar -zxf turnip.tar.gz -C /

    ldconfig

    rm -f /tmp/mesa.tar.gz /tmp/turnip.tar.gz
    echo "${MESA_VERSION}" > "$GPU_MARKER"
    echo "GPU_SETUP_COMPLETE"
'

echo "=== setup-gpu complete ==="

#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

echo "=== GPU driver setup $(date) ==="

# Detect GPU vendor at runtime
SCRIPTS_DIR="$HOME/scripts"
GPU_VENDOR=$("$SCRIPTS_DIR/detect-gpu.sh" 2>/dev/null || echo "unknown")
echo "GPU vendor detected: $GPU_VENDOR"

if [ "$GPU_VENDOR" = "adreno" ]; then
    echo "Adreno GPU detected — installing Zink+Turnip drivers..."

    proot-distro login ubuntu -- bash -c '
        set -euo pipefail

        GPU_MARKER="/root/.rlt-gpu-installed"

        # Force reinstall — this script only runs during setup (first install or version bump).
        # Stock Mesa provides driver files but they lack the Android-specific backends.
        rm -f "$GPU_MARKER"
        echo "GPU marker cleared — will install lfdevs Mesa+Turnip"

        echo "Installing Mesa + Turnip GPU drivers..."

        apt-get update -qq
        apt-get install -y -qq wget libx11-6 libxext6 libxfixes3 libxshmfence1 \
            libxxf86vm1 libdrm2 libexpat1 libwayland-client0 libelf1 \
            zlib1g libzstd1 > /dev/null 2>&1

        # Dynamically resolve latest Mesa release from GitHub API.
        # Tag format: "mesa-VERSION" (e.g. mesa-26.1.0-devel-20260208)
        # File format: "mesa-for-android-container_VERSION_ubuntu_noble_arm64.tar.gz"
        # Note: lfdevs now ships Mesa+Turnip in a single package (no separate turnip tarball)
        MESA_TAG=$(wget -qO- --timeout=10 \
            "https://api.github.com/repos/lfdevs/mesa-for-android-container/releases/latest" 2>/dev/null \
            | grep -o "\"tag_name\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | cut -d"\"" -f4)
        MESA_VERSION="${MESA_TAG#mesa-}"
        if [ -z "$MESA_TAG" ]; then
            MESA_TAG="mesa-26.1.0-devel-20260208"
            MESA_VERSION="26.1.0-devel-20260208"
            echo "WARNING: Using fallback Mesa version"
        fi
        MESA_BASE="https://github.com/lfdevs/mesa-for-android-container/releases/download"

        cd /tmp

        echo "Downloading Mesa+Turnip ${MESA_VERSION}..."
        # Try new naming (mesa-for-android-container_) first, fall back to old (mesa- + turnip-)
        if wget -q -O mesa.tar.gz "${MESA_BASE}/${MESA_TAG}/mesa-for-android-container_${MESA_VERSION}_ubuntu_noble_arm64.tar.gz" 2>/dev/null; then
            echo "Installing Mesa+Turnip (single package)..."
            tar -zxf mesa.tar.gz -C /
        else
            echo "Trying legacy naming..."
            wget -q -O mesa.tar.gz "${MESA_BASE}/${MESA_TAG}/mesa-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"
            wget -q -O turnip.tar.gz "${MESA_BASE}/${MESA_TAG}/turnip-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"
            echo "Installing Mesa..."
            tar -zxf mesa.tar.gz -C /
            echo "Installing Turnip..."
            tar -zxf turnip.tar.gz -C /
            rm -f /tmp/turnip.tar.gz
        fi

        ldconfig

        rm -f /tmp/mesa.tar.gz
        echo "${MESA_VERSION}" > "$GPU_MARKER"
        echo "GPU_SETUP_COMPLETE"
    '

elif [ "$GPU_VENDOR" = "mali" ]; then
    echo "Mali GPU detected — running Mali GPU setup..."
    "$SCRIPTS_DIR/setup-gpu-mali.sh"

else
    echo "Unknown GPU vendor ($GPU_VENDOR) — skipping GPU setup"
    echo "GPU_SETUP_COMPLETE"
fi

echo "=== setup-gpu complete ==="

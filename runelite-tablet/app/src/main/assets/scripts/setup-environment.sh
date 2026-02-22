#!/bin/bash
set -euo pipefail

# Check available disk space (need at least 1GB)
AVAIL_MB=$(df -m "$HOME" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -n "$AVAIL_MB" ] && [ "$AVAIL_MB" -lt 1000 ]; then
    echo "ERROR: Insufficient disk space. Need at least 1000MB, have ${AVAIL_MB}MB"
    exit 1
fi

echo "=== Step 1: Updating Termux packages ==="
pkg update -y
pkg install -y proot-distro wget termux-x11-nightly pulseaudio

echo "=== Step 2: Installing Ubuntu ARM64 ==="
if proot-distro list 2>/dev/null | grep -q "ubuntu"; then
    echo "Ubuntu already installed, skipping"
else
    proot-distro install ubuntu
fi

echo "=== Step 3: Installing Java inside Ubuntu ==="
proot-distro login ubuntu -- bash -c '
    set -euo pipefail
    APT_CACHE="/var/cache/apt/pkgcache.bin"
    if [ ! -f "$APT_CACHE" ] || [ "$(find "$APT_CACHE" -mmin +60 2>/dev/null)" ]; then
        apt-get update
    fi
    apt-get install -y openjdk-11-jdk wget ca-certificates
'

echo "=== Step 4: Downloading RuneLite ==="
proot-distro login ubuntu -- bash -c '
    set -euo pipefail
    RUNELITE_URL="https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar"
    mkdir -p /root/runelite
    if [ ! -f /root/runelite/RuneLite.jar ]; then
        wget -O /root/runelite/RuneLite.jar "$RUNELITE_URL"
    fi
    echo "RuneLite jar: $(ls -la /root/runelite/RuneLite.jar)"
'

echo "=== Setup complete ==="

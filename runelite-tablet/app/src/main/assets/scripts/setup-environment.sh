#!/bin/bash
set -euo pipefail

echo "=== Step 1: Updating Termux packages ==="
pkg update -y
pkg install -y proot-distro wget

echo "=== Step 2: Installing Ubuntu ARM64 ==="
if proot-distro list 2>/dev/null | grep -q "ubuntu"; then
    echo "Ubuntu already installed, skipping"
else
    proot-distro install ubuntu
fi

echo "=== Step 3: Installing Java inside Ubuntu ==="
proot-distro login ubuntu -- bash -c '
    set -euo pipefail
    apt-get update
    apt-get install -y openjdk-11-jdk wget ca-certificates
'

echo "=== Step 4: Downloading RuneLite ==="
proot-distro login ubuntu -- bash -c '
    set -euo pipefail
    RUNELITE_URL="https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar"
    mkdir -p /root/runelite
    wget -O /root/runelite/RuneLite.jar "$RUNELITE_URL"
    echo "RuneLite downloaded: $(ls -la /root/runelite/RuneLite.jar)"
'

echo "=== Step 5: Installing X11 packages ==="
pkg install -y termux-x11-nightly pulseaudio

echo "=== Setup complete ==="

#!/usr/bin/env bash
# Deploy a locally-built Mesa .deb to the test device and verify the
# glxinfo regression fix.
#
# Usage: scripts/deploy-mesa25-to-device.sh [serial]
#
# Assumes:
#   - third_party/termux-packages/output/ contains mesa_<ver>_aarch64.deb
#   - adb is on PATH
#   - device has Termux installed with com.termux uid

set -euo pipefail

SERIAL="${1:-R52X90378YB}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEBS_DIR="$REPO_ROOT/third_party/termux-packages/output"

if [ ! -d "$DEBS_DIR" ]; then
    echo "ERROR: $DEBS_DIR not found — run ./scripts/run-docker.sh ./build-package.sh -a aarch64 mesa inside third_party/termux-packages first" >&2
    exit 1
fi

MESA_DEB=$(ls -1 "$DEBS_DIR"/mesa_*_aarch64.deb 2>/dev/null | head -1)
if [ -z "$MESA_DEB" ]; then
    echo "ERROR: no mesa_*_aarch64.deb found in $DEBS_DIR" >&2
    exit 1
fi

echo "Found deb: $MESA_DEB"

# Push to /sdcard and copy into Termux home via run-as (run-as can read /sdcard
# under its own uid but cannot directly accept adb push into its sandbox).
MSYS2_ARG_CONV_EXCL='*' adb -s "$SERIAL" push "$MESA_DEB" /sdcard/mesa-rebuild.deb

adb -s "$SERIAL" shell "run-as com.termux cp /sdcard/mesa-rebuild.deb \$HOME/mesa-rebuild.deb"

# Before install: kill any running RL + X11 so Mesa's files can be overwritten.
adb -s "$SERIAL" shell "run-as com.termux pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true"
adb -s "$SERIAL" shell "run-as com.termux pkill -f 'launch-runelite-native' 2>/dev/null || true"
adb -s "$SERIAL" shell "run-as com.termux pkill -f 'virgl_test_server' 2>/dev/null || true"
adb -s "$SERIAL" shell "am force-stop com.runelitetablet"
adb -s "$SERIAL" shell "am force-stop com.termux.x11"
sleep 2

# Install with dpkg, allowing downgrade.
adb -s "$SERIAL" shell "run-as com.termux sh -c '
export PREFIX=/data/data/com.termux/files/usr
export PATH=\$PREFIX/bin:\$PATH
dpkg -i --force-downgrade \$HOME/mesa-rebuild.deb
'"

# Verify new version.
adb -s "$SERIAL" shell "run-as com.termux sh -c '
export PREFIX=/data/data/com.termux/files/usr
export PATH=\$PREFIX/bin:\$PATH
pkg list-installed 2>/dev/null | grep ^mesa
'"

echo ""
echo "Mesa deb installed. Now relaunch RuneLiteTablet and check client.log for"
echo "\"Error starting GPU plugin\" -> if gone, GLX handshake is fixed."

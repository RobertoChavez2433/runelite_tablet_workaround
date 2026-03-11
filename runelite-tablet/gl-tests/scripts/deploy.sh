#!/usr/bin/env bash
# deploy.sh — Push gl-tests files to Termux home via world-writable staging area.
# Workaround: App's filesDir is unreadable by Termux (different UID).
# Strategy: adb push -> /data/local/tmp/ (world-writable) -> cp via run-as com.termux
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Support multi-device: deploy.sh [-s SERIAL]
ADB_ARGS=()
if [[ "${1:-}" == "-s" ]] && [[ -n "${2:-}" ]]; then
    ADB_ARGS=(-s "$2")
    shift 2
fi

ADB=(adb "${ADB_ARGS[@]}")

# Verify device connected
if ! "${ADB[@]}" devices 2>/dev/null | grep -q 'device$'; then
    echo "ERROR: No device found. Connect via USB or set ADB_SERIAL." >&2
    exit 1
fi

STAGING_DIR="/data/local/tmp/gl-tests"
TERMUX_BASH="/data/data/com.termux/files/usr/bin/bash"
TERMUX_HOME="/data/data/com.termux/files/home"

echo "=== Deploying gl-tests to device ==="

# Step 1: Clean staging area
echo "[1/5] Cleaning staging area..."
"${ADB[@]}" shell "rm -rf $STAGING_DIR" 2>/dev/null || true
"${ADB[@]}" shell "mkdir -p $STAGING_DIR/scripts $STAGING_DIR/src"

# Step 2: Push files to staging
echo "[2/5] Pushing files to staging..."
"${ADB[@]}" push "$PROJECT_DIR/scripts/" "$STAGING_DIR/scripts/"
"${ADB[@]}" push "$PROJECT_DIR/src/" "$STAGING_DIR/src/"

# Step 3: Copy from staging to Termux home via run-as
echo "[3/5] Copying to Termux home via run-as..."
"${ADB[@]}" shell "run-as com.termux $TERMUX_BASH -lc 'rm -rf ~/gl-tests && cp -r $STAGING_DIR ~/gl-tests'"

# Step 4: Fix permissions and line endings
echo "[4/5] Fixing permissions and line endings..."
"${ADB[@]}" shell "run-as com.termux $TERMUX_BASH -lc '
    chmod +x ~/gl-tests/scripts/*.sh
    # Strip Windows CR from all text files defensively
    for f in ~/gl-tests/scripts/*.sh ~/gl-tests/src/*.c ~/gl-tests/src/*.h; do
        if [ -f \"\$f\" ]; then
            sed -i \"s/\\r\$//\" \"\$f\"
        fi
    done
'"

# Step 5: Clean up staging
echo "[5/5] Cleaning up staging..."
"${ADB[@]}" shell "rm -rf $STAGING_DIR" 2>/dev/null || true

# Verify deployment
echo ""
echo "=== Verifying deployment ==="
"${ADB[@]}" shell "run-as com.termux $TERMUX_BASH -lc 'ls -la ~/gl-tests/scripts/ ~/gl-tests/src/ 2>&1'"

echo ""
echo "=== Deploy complete ==="
echo "Next: Run tests via:"
echo "  adb shell \"run-as com.termux $TERMUX_BASH -lc '~/gl-tests/scripts/run-tests.sh --quick'\""

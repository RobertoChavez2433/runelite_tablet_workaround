#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# health-check.sh — Verify proot/Java/RuneLite integrity before launch.
# Always exits 0. Health status reported via structured output.

ROOTFS_DIR="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
RUNELITE_JAR="/root/runelite/RuneLite.jar"

echo "=== Running health check ==="

# Check proot/Ubuntu rootfs
if [ -d "$ROOTFS_DIR" ]; then
    echo "HEALTH proot OK"
else
    echo "HEALTH proot FAIL missing_rootfs"
fi

# Check Java binary inside proot
JAVA_CHECK=$(proot-distro login ubuntu -- which java < /dev/null 2>/dev/null || echo "")
if [ -n "$JAVA_CHECK" ]; then
    echo "HEALTH java OK"
else
    echo "HEALTH java FAIL binary_not_found"
fi

# Check RuneLite.jar exists and is valid
JAR_CHECK=$(proot-distro login ubuntu -- bash -c "
    if [ -f '$RUNELITE_JAR' ]; then
        SIZE=\$(wc -c < '$RUNELITE_JAR' 2>/dev/null || echo 0)
        if [ \"\$SIZE\" -gt 1048576 ]; then
            echo 'OK'
        else
            echo 'FAIL jar_too_small'
        fi
    else
        echo 'FAIL jar_missing'
    fi
" < /dev/null 2>/dev/null || echo "FAIL proot_error")

echo "HEALTH runelite $JAR_CHECK"

echo "=== health-check complete ==="

#!/data/data/com.termux/files/usr/bin/bash
# User namespace evaluation — tests unshare as proot replacement
# Compares stime/utime ratio under proot vs unshare
# Usage: test-user-ns.sh

echo "USER_NS_EVAL: start"

# Step 1: Verify user namespaces work
UNSHARE_TEST=$(unshare --user --map-root-user echo "ns_ok" 2>&1)
if ! echo "$UNSHARE_TEST" | grep -q "ns_ok"; then
    echo "USER_NS_EVAL: verdict=blocked reason=unshare_failed detail=$UNSHARE_TEST"
    exit 0
fi

echo "USER_NS_EVAL: unshare works"

# Step 2: Run a CPU-bound workload under proot and measure kernel time
echo "USER_NS_EVAL: measuring proot overhead..."
PROOT_START=$(date +%s%N)
proot-distro login ubuntu --shared-tmp -- bash -c '
    # Busy loop: count to 1M
    i=0; while [ $i -lt 1000000 ]; do i=$((i+1)); done
    cat /proc/self/stat
' 2>/dev/null > /tmp/proot-stat.txt
PROOT_END=$(date +%s%N)
PROOT_WALL_MS=$(( (PROOT_END - PROOT_START) / 1000000 ))

PROOT_STAT=$(cat /tmp/proot-stat.txt 2>/dev/null)
if [ -n "$PROOT_STAT" ]; then
    PROOT_UTIME=$(echo "$PROOT_STAT" | awk '{print $14}')
    PROOT_STIME=$(echo "$PROOT_STAT" | awk '{print $15}')
    PROOT_TOTAL=$((PROOT_UTIME + PROOT_STIME))
    if [ "$PROOT_TOTAL" -gt 0 ] 2>/dev/null; then
        PROOT_RATIO=$(awk "BEGIN {printf \"%.2f\", $PROOT_STIME / $PROOT_TOTAL}")
    else
        PROOT_RATIO="0.00"
    fi
    echo "USER_NS_EVAL: proot utime=$PROOT_UTIME stime=$PROOT_STIME kernel_ratio=$PROOT_RATIO wall_ms=$PROOT_WALL_MS"
fi

# Step 3: Run same workload under unshare and measure
echo "USER_NS_EVAL: measuring unshare overhead..."
UNSHARE_START=$(date +%s%N)
unshare --user --map-root-user bash -c '
    i=0; while [ $i -lt 1000000 ]; do i=$((i+1)); done
    cat /proc/self/stat
' 2>/dev/null > /tmp/unshare-stat.txt
UNSHARE_END=$(date +%s%N)
UNSHARE_WALL_MS=$(( (UNSHARE_END - UNSHARE_START) / 1000000 ))

UNSHARE_STAT=$(cat /tmp/unshare-stat.txt 2>/dev/null)
if [ -n "$UNSHARE_STAT" ]; then
    UNSHARE_UTIME=$(echo "$UNSHARE_STAT" | awk '{print $14}')
    UNSHARE_STIME=$(echo "$UNSHARE_STAT" | awk '{print $15}')
    UNSHARE_TOTAL=$((UNSHARE_UTIME + UNSHARE_STIME))
    if [ "$UNSHARE_TOTAL" -gt 0 ] 2>/dev/null; then
        UNSHARE_RATIO=$(awk "BEGIN {printf \"%.2f\", $UNSHARE_STIME / $UNSHARE_TOTAL}")
    else
        UNSHARE_RATIO="0.00"
    fi
    echo "USER_NS_EVAL: unshare utime=$UNSHARE_UTIME stime=$UNSHARE_STIME kernel_ratio=$UNSHARE_RATIO wall_ms=$UNSHARE_WALL_MS"
fi

echo "USER_NS_EVAL: comparison proot_ratio=$PROOT_RATIO unshare_ratio=$UNSHARE_RATIO proot_wall=${PROOT_WALL_MS}ms unshare_wall=${UNSHARE_WALL_MS}ms"
echo "USER_NS_EVAL: done"

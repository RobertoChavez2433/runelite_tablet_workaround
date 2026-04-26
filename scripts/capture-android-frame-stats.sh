#!/usr/bin/env bash
# capture-android-frame-stats.sh — host-side companion for S81 logging audit.
#
# Termux UID (u0_a164) cannot invoke `dumpsys` (SecurityException: Permission
# Denial — DUMP is signature-protected). The adb shell UID (uid=2000/shell)
# CAN, so we collect SurfaceFlinger latency + gfxinfo framestats from the host
# in parallel with device-side perf-sampler.sh.
#
# Runs until Ctrl-C. Writes three log streams:
#   <outdir>/sf-list.log         layer list every iteration (grows)
#   <outdir>/sf-latency.log      `--latency <layer>` for every RLT/Xlorie layer
#   <outdir>/gfxinfo.log         `dumpsys gfxinfo` framestats for RLT + termux.x11
#
# Output layout is flat text, one timestamped block per poll tick. Parse with
# awk/grep. Comparable to rlawt [rlawt-perf] + XloriePerf windows — timestamps
# are CLOCK_MONOTONIC on Android (matches both).
#
# Usage:
#   scripts/capture-android-frame-stats.sh <device-serial> [outdir] [interval_sec]
# Example:
#   scripts/capture-android-frame-stats.sh R52X90378YB runelite-tablet/docs/logs/s81-capture 2
# NOTE: do not use `set -e` — the poll loop relies on greps that return 1 when
# no matching layer exists yet (Activity not started), and pipefail would kill
# the whole script on the first iteration before the user hits Launch.
set -uo pipefail

SERIAL="${1:?device serial required (adb devices)}"
OUTDIR="${2:-runelite-tablet/docs/logs/capture-$(date +%Y%m%d-%H%M%S)}"
INTERVAL="${3:-2}"

mkdir -p "$OUTDIR"
SF_LIST_LOG="$OUTDIR/sf-list.log"
SF_LATENCY_LOG="$OUTDIR/sf-latency.log"
GFX_LOG="$OUTDIR/gfxinfo.log"
META_LOG="$OUTDIR/meta.log"

{
    echo "=== capture-android-frame-stats.sh start $(date -u +%FT%TZ) ==="
    echo "device=$SERIAL interval_sec=$INTERVAL outdir=$OUTDIR"
    adb -s "$SERIAL" shell getprop ro.build.fingerprint
    adb -s "$SERIAL" shell getprop ro.product.model
    adb -s "$SERIAL" shell dumpsys display 2>/dev/null | grep -E 'mDisplayId=0|mCurrentMode|mBaseDisplayMode|mSupportedModes|mDefaultModeId' | sed 's/^/  /'
} > "$META_LOG"

# Truncate the streaming logs
: > "$SF_LIST_LOG"
: > "$SF_LATENCY_LOG"
: > "$GFX_LOG"

echo "Writing $SF_LIST_LOG $SF_LATENCY_LOG $GFX_LOG" >&2
echo "Ctrl-C to stop." >&2

cleanup() {
    echo "=== capture stop $(date -u +%FT%TZ) ===" >> "$META_LOG"
    echo "Stopped." >&2
    exit 0
}
trap cleanup INT TERM

# gfxinfo is per-package, every 5 ticks (≈10s at interval=2)
gfx_every=5
tick=0

while :; do
    ts="$(date -u +%FT%T.%3NZ)"
    # Dump the layer list so we can figure out which layer is the SurfaceView.
    # The list changes when Activities start/stop — grep it each tick and use
    # the names downstream.
    echo "=== $ts dumpsys SurfaceFlinger --list ===" >> "$SF_LIST_LOG"
    adb -s "$SERIAL" shell 'dumpsys SurfaceFlinger --list 2>&1' \
        | grep -iE 'runelitetablet|termux\.x11|SurfaceView|ActivityRecord.*runelite' \
        >> "$SF_LIST_LOG" 2>&1 || true

    # For each matching layer, capture its --latency block. SurfaceFlinger's
    # --latency returns 128 rows of: <desiredPresent> <actualPresent> <frameReady>
    # nanoseconds (CLOCK_MONOTONIC). Zero rows are unused slots.
    while read -r layer; do
        [ -z "$layer" ] && continue
        echo "=== $ts --latency \"$layer\" ===" >> "$SF_LATENCY_LOG"
        adb -s "$SERIAL" shell "dumpsys SurfaceFlinger --latency \"$layer\" 2>&1" \
            >> "$SF_LATENCY_LOG" 2>&1 || true
    done < <(adb -s "$SERIAL" shell 'dumpsys SurfaceFlinger --list 2>&1' \
             | grep -iE 'SurfaceView|runelitetablet.*HybridX11HostActivity|termux\.x11' \
             | sed -E 's/^[^{]*\{//; s/\}[^}]*$//' || true)

    # gfxinfo framestats every gfx_every iterations. This call also resets stats,
    # so we space it out to avoid flooding.
    if [ $((tick % gfx_every)) -eq 0 ]; then
        for pkg in com.runelitetablet com.termux.x11; do
            echo "=== $ts dumpsys gfxinfo $pkg framestats ===" >> "$GFX_LOG"
            adb -s "$SERIAL" shell "dumpsys gfxinfo $pkg framestats 2>&1" \
                >> "$GFX_LOG" 2>&1 || true
        done
    fi

    tick=$((tick + 1))
    sleep "$INTERVAL"
done

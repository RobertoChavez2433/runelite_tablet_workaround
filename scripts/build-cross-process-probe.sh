#!/usr/bin/env bash
# build-cross-process-probe.sh — compile, push, and run the cross-process
# AHardwareBuffer + EGLImage de-risker on the Tab S10 Ultra.
#
# This is the S82 task #25 de-risker for direct-Android-surface (sub-problem
# 5a — cross-process surface handoff). See:
#   docs/s82-capture/direct-android-blocker-3-scope.md
#
# Run: ./scripts/build-cross-process-probe.sh [iters]
#   iters defaults to 20. The scope doc calls for 100; pass 100 to match.
#
# Requirements: Android NDK 28+ at $NDK_HOME (auto-detected from common
# paths) and the S10 tablet on adb (serial R52X90378YB).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE_SRC="$REPO_ROOT/scripts/cross-process-surface-probe.c"
OUT_DIR="$REPO_ROOT/docs/s82-capture"
PROBE_BIN="$OUT_DIR/cross-process-surface-probe"
PROBE_JSON="$OUT_DIR/cross-process-surface-probe.json"
PROBE_STDERR="$OUT_DIR/cross-process-surface-probe.stderr.log"
DEVICE_PATH="/data/local/tmp/cross-process-surface-probe"
ANDROID_SERIAL="${ANDROID_SERIAL:-R52X90378YB}"
API_LEVEL="${API_LEVEL:-34}"
ITERS="${1:-20}"

mkdir -p "$OUT_DIR"

# --- Locate NDK clang (mirrors build-egl-probe.sh) ---
candidate_ndk_roots=(
    "${NDK_HOME:-}"
    "${ANDROID_NDK_HOME:-}"
    "${ANDROID_NDK:-}"
    "$HOME/AppData/Local/Android/Sdk/ndk/28.2.13676358"
    "$HOME/AppData/Local/Android/Sdk/ndk/27.0.12077973"
    "$HOME/AppData/Local/Android/Sdk/ndk/26.1.10909125"
    "/c/Users/${USER:-${USERNAME:-rseba}}/AppData/Local/Android/Sdk/ndk/28.2.13676358"
)
NDK_ROOT=""
for c in "${candidate_ndk_roots[@]}"; do
    [ -n "$c" ] && [ -d "$c/toolchains/llvm/prebuilt" ] && NDK_ROOT="$c" && break
done
if [ -z "$NDK_ROOT" ]; then
    echo "ERROR: NDK not found. Set NDK_HOME or install Android NDK." >&2
    exit 1
fi

host_dir=""
for h in windows-x86_64 linux-x86_64 darwin-x86_64 darwin-arm64; do
    [ -d "$NDK_ROOT/toolchains/llvm/prebuilt/$h/bin" ] && host_dir="$h" && break
done
if [ -z "$host_dir" ]; then
    echo "ERROR: no host toolchain found under $NDK_ROOT/toolchains/llvm/prebuilt/" >&2
    exit 1
fi

CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/$host_dir/bin/aarch64-linux-android${API_LEVEL}-clang"
if [ ! -x "$CLANG" ] && [ -f "${CLANG}.cmd" ]; then
    CLANG="${CLANG}.cmd"
fi
if [ ! -x "$CLANG" ] && [ ! -f "$CLANG" ]; then
    echo "ERROR: NDK clang not found at $CLANG" >&2
    exit 1
fi

echo "[build] NDK=$NDK_ROOT host=$host_dir api=$API_LEVEL"
echo "[build] clang=$CLANG"

"$CLANG" -O0 -g -Wall -Wextra \
    "$PROBE_SRC" \
    -lEGL -lGLESv3 -lnativewindow -llog \
    -o "$PROBE_BIN"

echo "[build] OK -> $PROBE_BIN ($(stat -c%s "$PROBE_BIN" 2>/dev/null || stat -f%z "$PROBE_BIN") bytes)"

# --- Push + run ---
export MSYS_NO_PATHCONV=1
PROBE_BIN_WIN="$PROBE_BIN"
case "$PROBE_BIN" in
    /c/*) PROBE_BIN_WIN="C:${PROBE_BIN#/c}" ;;
    /d/*) PROBE_BIN_WIN="D:${PROBE_BIN#/d}" ;;
esac
adb -s "$ANDROID_SERIAL" push "$PROBE_BIN_WIN" "$DEVICE_PATH" >/dev/null
adb -s "$ANDROID_SERIAL" shell chmod 0755 "$DEVICE_PATH"
echo "[run] adb shell $DEVICE_PATH $ITERS"
# Capture stderr separately so producer/consumer log lines survive — JSON
# verdict is on stdout.
adb -s "$ANDROID_SERIAL" shell "$DEVICE_PATH $ITERS 2>/data/local/tmp/cps-probe.err; echo --STDERR--; cat /data/local/tmp/cps-probe.err; rm -f /data/local/tmp/cps-probe.err" \
    | tee /tmp/cps-probe-combined.log >/dev/null

# Split stdout JSON vs stderr log lines
awk '
    /^--STDERR--$/ { in_err = 1; next }
    in_err == 0 { print > "/tmp/cps-probe.json" }
    in_err == 1 { print > "/tmp/cps-probe.stderr" }
' /tmp/cps-probe-combined.log

cp /tmp/cps-probe.json "$PROBE_JSON"
cp /tmp/cps-probe.stderr "$PROBE_STDERR"
echo "[run] saved -> $PROBE_JSON"
echo "[run] stderr -> $PROBE_STDERR"

# --- Verdict surface ---
echo
echo "=== Verdict ==="
grep -E '"(cross_process_ahb_egl_import|summary|fail_count|verifies_ok|fd_start|fd_end)"' "$PROBE_JSON" || true
echo
echo "=== Tail of stderr ==="
tail -20 "$PROBE_STDERR" || true

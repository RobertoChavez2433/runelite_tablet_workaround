#!/usr/bin/env bash
# build-egl-probe.sh — compile, push, and run the Mali Android EGL/GLES probe.
#
# This is a feasibility check for the direct-android-surface path. Output is
# JSON to stdout + saved as $REPO/docs/s82-capture/egl-compat-probe.json.
#
# Goal of the probe: clear blockers #1 (GLES feature surface vs RL GpuPlugin
# requirements) and #2 (Mali EGL/GLES extensions) BEFORE we commit code to
# replacing librlawt.so. If verdict.summary contains "FAIL" we DO NOT pursue
# direct-android via the simple path.
#
# Run: ./scripts/build-egl-probe.sh
#
# Requirements: Android NDK 28+ at $NDK_HOME (auto-detected from common paths)
# and the S10 tablet on adb (serial R52X90378YB).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE_SRC="$REPO_ROOT/scripts/egl-compat-probe.c"
OUT_DIR="$REPO_ROOT/docs/s82-capture"
PROBE_BIN="$OUT_DIR/egl-compat-probe"
PROBE_JSON="$OUT_DIR/egl-compat-probe.json"
DEVICE_PATH="/data/local/tmp/egl-compat-probe"
ANDROID_SERIAL="${ANDROID_SERIAL:-R52X90378YB}"
API_LEVEL="${API_LEVEL:-34}"

mkdir -p "$OUT_DIR"

# --- Locate NDK clang ---
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

# Pick the host toolchain dir (windows-x86_64 or linux-x86_64 or darwin-x86_64).
host_dir=""
for h in windows-x86_64 linux-x86_64 darwin-x86_64 darwin-arm64; do
    [ -d "$NDK_ROOT/toolchains/llvm/prebuilt/$h/bin" ] && host_dir="$h" && break
done
if [ -z "$host_dir" ]; then
    echo "ERROR: no host toolchain found under $NDK_ROOT/toolchains/llvm/prebuilt/" >&2
    exit 1
fi

CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/$host_dir/bin/aarch64-linux-android${API_LEVEL}-clang"
# On Windows the wrapper is .cmd; the bare name fails. Try both.
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
    -lEGL -lGLESv3 -llog \
    -o "$PROBE_BIN"

echo "[build] OK -> $PROBE_BIN ($(stat -c%s "$PROBE_BIN" 2>/dev/null || stat -f%z "$PROBE_BIN") bytes)"

# --- Push + run ---
# Git-Bash on Windows mangles `/data/local/tmp/...` into MSYS-rooted paths
# when adb's argv parser sees a leading slash. MSYS_NO_PATHCONV=1 disables
# the conversion. We also have to convert PROBE_BIN from POSIX (/c/...) to
# Windows (C:/...) for adb's local-file argument since adb is a Windows binary.
export MSYS_NO_PATHCONV=1
PROBE_BIN_WIN="$PROBE_BIN"
case "$PROBE_BIN" in
    /c/*) PROBE_BIN_WIN="C:${PROBE_BIN#/c}" ;;
    /d/*) PROBE_BIN_WIN="D:${PROBE_BIN#/d}" ;;
esac
adb -s "$ANDROID_SERIAL" push "$PROBE_BIN_WIN" "$DEVICE_PATH" >/dev/null
adb -s "$ANDROID_SERIAL" shell chmod 0755 "$DEVICE_PATH"
echo "[run] adb shell $DEVICE_PATH"
adb -s "$ANDROID_SERIAL" shell "$DEVICE_PATH" | tee "$PROBE_JSON"
echo "[run] saved -> $PROBE_JSON"

# --- Verdict surface (cheap grep — full JSON is on disk) ---
echo
echo "=== Verdict ==="
grep -E '"(direct_android_blocker_[12]_clear|summary|version|renderer|vendor)"' "$PROBE_JSON" || true

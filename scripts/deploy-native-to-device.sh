#!/usr/bin/env bash
#
# Rebuild + install BOTH APKs that ship native Xlorie code.
#
# Why this exists:
#   RuneLiteTablet (`com.runelitetablet`) and the Termux:X11 fork
#   (`com.termux.x11`) each ship their own libXlorie.so. The X server process
#   runs from com.termux.x11; the in-app LorieView uses the RuneLiteTablet
#   copy. Changes under `third_party/termux-x11-upstream/app/src/main/cpp/`
#   therefore have to rebuild + reinstall BOTH APKs, and the X server has to
#   be killed so it reloads the new .so on next launch.
#
# Usage:
#   ./scripts/deploy-native-to-device.sh [-s SERIAL]
#     -s SERIAL   target specific device (default: first `adb devices` entry)
#     --only=rlt  build+install only runelite-tablet
#     --only=x11  build+install only termux-x11-upstream
#     --no-kill   skip killing the running X server
#
# Exit codes: 0 ok; non-zero = a build/install failed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RLT_DIR="$REPO_ROOT/runelite-tablet"
X11_DIR="$REPO_ROOT/third_party/termux-x11-upstream"

SERIAL=""
ONLY=""
KILL_X11=1

while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2;;
        --only=rlt) ONLY="rlt"; shift;;
        --only=x11) ONLY="x11"; shift;;
        --no-kill) KILL_X11=0; shift;;
        *) echo "unknown arg: $1" >&2; exit 2;;
    esac
done

ADB=(adb)
if [ -n "$SERIAL" ]; then
    ADB+=(-s "$SERIAL")
fi

# ---------------------------------------------------------------- build RLT
if [ "$ONLY" != "x11" ]; then
    echo ">>> Building runelite-tablet debug APK"
    (cd "$RLT_DIR" && ./gradlew :app:assembleDebug)
fi

# ------------------------------------------------------------ build termux-x11
if [ "$ONLY" != "rlt" ]; then
    echo ">>> Building termux-x11-upstream debug APK (Slice 4: Xlorie patches)"
    (cd "$X11_DIR" && ./gradlew :app:assembleDebug)
fi

# -------------------------------------------------------------------- install
if [ "$ONLY" != "x11" ]; then
    RLT_APK="$RLT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$RLT_APK" ] || { echo "RLT APK missing: $RLT_APK" >&2; exit 3; }
    echo ">>> Installing $RLT_APK"
    "${ADB[@]}" install -r -d "$RLT_APK"
    "${ADB[@]}" shell pm grant com.runelitetablet com.termux.permission.RUN_COMMAND
fi

if [ "$ONLY" != "rlt" ]; then
    X11_APK="$X11_DIR/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
    [ -f "$X11_APK" ] || { echo "Termux:X11 APK missing: $X11_APK" >&2; exit 4; }
    echo ">>> Installing $X11_APK (com.termux.x11)"
    "${ADB[@]}" install -r "$X11_APK"
fi

# ------------------------------------------------------------- kill X server
# Android does not reload native libraries into a running process. The X
# server has to die so the next launch re-dlopen()s libXlorie.so from the
# freshly installed APK. This is disruptive — it ends any in-progress
# RuneLite session — so it is the last step and can be skipped via --no-kill.
if [ "$KILL_X11" = "1" ] && [ "$ONLY" != "rlt" ]; then
    echo ">>> Stopping running X server so next launch reloads the new .so"
    "${ADB[@]}" shell "am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true" >/dev/null
    # Fallback: force-kill any surviving app_process that hosts the X server
    "${ADB[@]}" shell "run-as com.termux sh -c 'for p in \$(pgrep -f com.termux.x11.Loader 2>/dev/null); do kill -9 \$p 2>/dev/null || true; done'" >/dev/null 2>&1 || true
fi

echo ">>> Done. Relaunch RuneLite from the app to pick up new native code."

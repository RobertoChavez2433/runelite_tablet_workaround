#!/data/data/com.termux/files/usr/bin/bash
# Phase 2.1 ad-hoc test wrapper: double-forks the native launcher so adb
# doesn't block on the JVM and the process survives adb disconnection.
# Written for on-device ad-hoc verification, not a permanent artifact —
# once Phase 2.2 wires the Kotlin service, this wrapper goes away.
export RLT_NATIVE_TERMUX=1
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export TMPDIR=/data/data/com.termux/files/usr/tmp
LAUNCHER=/data/data/com.termux/files/home/scripts/launch-runelite-native.sh
BASH=/data/data/com.termux/files/usr/bin/bash
BG_LOG=/data/data/com.termux/files/home/native-launcher-bg.log
setsid "$BASH" "$LAUNCHER" </dev/null >"$BG_LOG" 2>&1 &
CHILD=$!
disown "$CHILD" 2>/dev/null || true
echo "DETACHED launcher pid=$CHILD log=$BG_LOG"
exit 0

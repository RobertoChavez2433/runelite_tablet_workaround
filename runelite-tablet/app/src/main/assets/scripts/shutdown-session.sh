#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail

echo "=== Graceful shutdown $(date) ==="

# 1. RuneLite (Java) — SIGTERM first, SIGKILL fallback
JAVA_PID=$(cat "$HOME/.rlt-session.pid" 2>/dev/null)
if [ -z "$JAVA_PID" ]; then
    JAVA_PID=$(pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null | head -1)
fi
if [ -n "$JAVA_PID" ]; then
    echo "Stopping RuneLite (PID $JAVA_PID)..."
    kill "$JAVA_PID" 2>/dev/null
    for i in $(seq 1 50); do
        kill -0 "$JAVA_PID" 2>/dev/null || break
        sleep 0.1
    done
    kill -9 "$JAVA_PID" 2>/dev/null || true
fi

# 2. Openbox
pkill -f 'openbox' 2>/dev/null || true

# 3. Proot sessions
pkill -f 'proot-distro' 2>/dev/null || true
pkill -f 'proot --' 2>/dev/null || true

# 4. PulseAudio
pulseaudio --kill 2>/dev/null || true

# 5. X11 server process
pkill -f 'termux-x11' 2>/dev/null || true

# 6. Termux:X11 Android app
am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true

# Cleanup files
rm -f "$HOME/.rlt-session.pid" 2>/dev/null || true
rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
rm -f "$HOME/.rlt-launch-env.sh" 2>/dev/null || true

echo "SHUTDOWN_COMPLETE"

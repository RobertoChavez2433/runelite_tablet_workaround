#!/bin/bash
set -euo pipefail

# Start PulseAudio for game audio
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
    --exit-idle-time=-1 2>/dev/null || true

# Start Termux:X11
termux-x11 :0 &
X11_PID=$!
trap "kill $X11_PID 2>/dev/null" EXIT

# Wait for X11 socket to be ready (up to 5 seconds)
for i in $(seq 1 25); do
    [ -e /tmp/.X11-unix/X0 ] && break
    sleep 0.2
done

if [ ! -e /tmp/.X11-unix/X0 ]; then
    echo "ERROR: X11 socket not ready after 5 seconds"
    exit 1
fi

# Launch RuneLite inside proot
proot-distro login ubuntu -- bash -c '
    export DISPLAY=:0
    export PULSE_SERVER=tcp:127.0.0.1

    cd /root/runelite
    java -Xmx2g -Xms512m -XX:+UseG1GC -jar RuneLite.jar --insecure-write-credentials
'

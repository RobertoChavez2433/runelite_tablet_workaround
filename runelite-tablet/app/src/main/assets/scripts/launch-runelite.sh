#!/bin/bash
set -euo pipefail

# Start PulseAudio for game audio
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
    --exit-idle-time=-1 2>/dev/null || true

# Start Termux:X11
termux-x11 :0 &
sleep 2

# Launch RuneLite inside proot
proot-distro login ubuntu -- bash -c '
    export DISPLAY=:0
    export PULSE_SERVER=tcp:127.0.0.1

    cd /root/runelite
    java -jar RuneLite.jar --insecure-write-credentials
'

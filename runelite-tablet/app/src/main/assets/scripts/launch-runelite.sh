#!/data/data/com.termux/files/usr/bin/bash
# No set -e — we want to see ALL errors, not exit on the first one
set -uo pipefail

LOGFILE="$HOME/runelite-launch.log"
# Termux uses $PREFIX/tmp, not /tmp — resolve the actual X11 socket path
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
echo "=== RuneLite launch $(date) ===" | tee "$LOGFILE"

# Start PulseAudio for game audio
echo "Starting PulseAudio..." | tee -a "$LOGFILE"
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
    --exit-idle-time=-1 2>&1 | tee -a "$LOGFILE" || true

# Start Termux:X11
echo "Starting Termux:X11..." | tee -a "$LOGFILE"
termux-x11 :0 &
X11_PID=$!
trap "kill $X11_PID 2>/dev/null" EXIT

# Wait for X11 socket to be ready (up to 10 seconds)
echo "Waiting for X11 socket..." | tee -a "$LOGFILE"
X11_READY=false
for i in $(seq 1 50); do
    if [ -e "$X11_SOCKET_DIR/X0" ]; then
        X11_READY=true
        break
    fi
    sleep 0.2
done

if [ "$X11_READY" = false ]; then
    echo "ERROR: X11 socket not ready after 10 seconds" | tee -a "$LOGFILE"
    echo "Contents of $PREFIX/tmp/:" | tee -a "$LOGFILE"
    ls -laR "$PREFIX/tmp/" 2>&1 | tee -a "$LOGFILE"
    echo "" | tee -a "$LOGFILE"
    echo "Is Termux:X11 app running? Check that it's open." | tee -a "$LOGFILE"
    echo "" | tee -a "$LOGFILE"
    echo "Press Enter to exit..."
    [ -t 0 ] && read -r || sleep 5
    exit 1
fi

echo "X11 socket ready" | tee -a "$LOGFILE"

# Launch RuneLite inside proot.
# Bind-mount Termux's X11 socket directory into proot so DISPLAY=:0 can find it.
# Without this, proot's /tmp is isolated and the X11 socket is invisible to RuneLite.
# Use heredoc (bash -s) to avoid single-quote escaping issues with embedded config.
echo "Launching RuneLite..." | tee -a "$LOGFILE"
proot-distro login ubuntu --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix" -- bash -s << 'PROOT_SCRIPT' 2>&1 | tee -a "$LOGFILE"
    export DISPLAY=:0
    # Explicit port 4713 — avoids silent failure if PulseAudio uses a non-default port
    export PULSE_SERVER=tcp:127.0.0.1:4713

    # Start openbox window manager so RuneLite is maximized to fill the display.
    # Without a WM, X11 windows default to 1038x503 (OSRS default) on bare X11.
    # The rc.xml rule maximizes all windows and removes decorations for full-screen gaming.
    mkdir -p /root/.config/openbox
    cat > /root/.config/openbox/rc.xml << 'OBCFG'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc"
    xmlns:xi="http://www.w3.org/2001/XInclude">
  <applications>
    <application class="*" groupclass="*">
      <decor>no</decor>
      <maximized>yes</maximized>
    </application>
  </applications>
</openbox_config>
OBCFG
    openbox --sm-disable &
    sleep 0.5

    cd /root/runelite

    # Build classpath from repository2 jars downloaded by the launcher
    REPO_DIR="/root/.runelite/repository2"
    if [ -d "$REPO_DIR" ] && ls "$REPO_DIR"/*.jar > /dev/null 2>&1; then
        CP=$(echo "$REPO_DIR"/*.jar | tr " " ":")
        echo "Running RuneLite client directly (classpath: $CP)" >&2
        exec java -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
            -Dsun.java2d.opengl=false \
            -Drunelite.launcher.version=2.7.6 \
            -cp "$CP" \
            net.runelite.client.RuneLite --insecure-write-credentials
    else
        echo "No client jars found — running launcher to download them first" >&2
        java -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
            -jar RuneLite.jar --insecure-write-credentials
    fi
PROOT_SCRIPT

# PIPESTATUS[0] captures proot-distro exit code, not tee's exit code.
# Under pipefail, $? after a pipe reflects tee (rightmost), which exits 0 even when
# proot fails, causing spurious "RuneLite failed" messages.
EXIT_CODE=${PIPESTATUS[0]}
echo "RuneLite exited with code: $EXIT_CODE" | tee -a "$LOGFILE"

if [ $EXIT_CODE -ne 0 ]; then
    echo "" | tee -a "$LOGFILE"
    echo "RuneLite failed. Log saved to: $LOGFILE" | tee -a "$LOGFILE"
    echo "Press Enter to exit..."
    [ -t 0 ] && read -r || sleep 5
fi

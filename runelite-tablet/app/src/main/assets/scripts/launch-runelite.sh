#!/data/data/com.termux/files/usr/bin/bash
# No set -e — we want to see ALL errors, not exit on the first one
set -uo pipefail

LOGFILE="$HOME/runelite-launch.log"
# Termux uses $PREFIX/tmp, not /tmp — resolve the actual X11 socket path
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
echo "=== RuneLite launch $(date) ===" | tee "$LOGFILE"

# --- Credential injection via temp env file ---
# The Android app writes JX_* env vars to a temp file and passes its path as $1.
# Source it here (in the outer Termux shell) so we can forward the vars into proot.
# The file is deleted immediately after sourcing for security.
ENV_FILE="${1:-}"
if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    echo "Sourcing credentials from env file..." | tee -a "$LOGFILE"
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    rm -f "$ENV_FILE"
    # Never log credential values — only confirm presence
    [ -n "${JX_SESSION_ID:-}" ] && echo "  JX_SESSION_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_CHARACTER_ID:-}" ] && echo "  JX_CHARACTER_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_DISPLAY_NAME:-}" ] && echo "  JX_DISPLAY_NAME=***" | tee -a "$LOGFILE"
    [ -n "${JX_ACCESS_TOKEN:-}" ] && echo "  JX_ACCESS_TOKEN=***" | tee -a "$LOGFILE"
else
    echo "No credentials env file provided — RuneLite will show its own login" | tee -a "$LOGFILE"
fi

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

# Auto-switch to Termux:X11 so the user sees the display without manually switching apps.
echo "Switching to Termux:X11..." | tee -a "$LOGFILE"
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity 2>&1 | tee -a "$LOGFILE" || true

# Set Termux:X11 preferences from the shell as backup.
# The primary mechanism is the CHANGE_PREFERENCE broadcast sent from the Kotlin launch() method.
echo "Setting Termux:X11 preferences (shell backup)..." | tee -a "$LOGFILE"
timeout 5 termux-x11-preference "fullscreen"="true" 2>&1 | tee -a "$LOGFILE" || true
timeout 5 termux-x11-preference "showAdditionalKbd"="false" 2>&1 | tee -a "$LOGFILE" || true
timeout 5 termux-x11-preference "displayResolutionMode"="native" 2>&1 | tee -a "$LOGFILE" || true

# Build credential env var forwarding for proot.
# Env vars set in the outer Termux shell are NOT inherited by proot-distro login (Spike C result).
# We write a temp env file inside proot and source it in the bash -c block.
PROOT_ENV_FILE=""
if [ -n "${JX_SESSION_ID:-}" ]; then
    PROOT_ENV_FILE="/tmp/.rlt-creds-$$.sh"
    # Write the env file into the Termux tmp dir (which gets bind-mounted into proot as /tmp)
    TERMUX_ENV_FILE="$PREFIX/tmp/.rlt-creds-$$.sh"
    {
        printf "export JX_SESSION_ID=%q\n" "${JX_SESSION_ID}"
        [ -n "${JX_CHARACTER_ID:-}" ] && printf "export JX_CHARACTER_ID=%q\n" "${JX_CHARACTER_ID}"
        [ -n "${JX_DISPLAY_NAME:-}" ] && printf "export JX_DISPLAY_NAME=%q\n" "${JX_DISPLAY_NAME}"
        [ -n "${JX_ACCESS_TOKEN:-}" ] && printf "export JX_ACCESS_TOKEN=%q\n" "${JX_ACCESS_TOKEN}"
        [ -n "${JX_REFRESH_TOKEN:-}" ] && printf "export JX_REFRESH_TOKEN=%q\n" "${JX_REFRESH_TOKEN}"
    } > "$TERMUX_ENV_FILE"
    chmod 600 "$TERMUX_ENV_FILE"
    echo "Credential env file written for proot forwarding" | tee -a "$LOGFILE"
fi

# Launch RuneLite inside proot.
# Bind-mount Termux's X11 socket directory into proot so DISPLAY=:0 can find it.
echo "Launching RuneLite..." | tee -a "$LOGFILE"
proot-distro login ubuntu --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix" --bind "$PREFIX/tmp:/tmp" -- bash -c "
    export DISPLAY=:0
    export PULSE_SERVER=tcp:127.0.0.1:4713

    # Source credentials if available (Spike C: must source INSIDE proot)
    if [ -n '${PROOT_ENV_FILE}' ] && [ -f '${PROOT_ENV_FILE}' ]; then
        source '${PROOT_ENV_FILE}'
        rm -f '${PROOT_ENV_FILE}'
    fi

    # Set display resolution via xrandr for Tab S10 Ultra (2960x1848)
    # xrandr is installed by install-java.sh (x11-xserver-utils package)
    # These commands are best-effort — display works at default res if they fail
    sleep 0.5  # Brief delay for X11 to initialize
    xrandr --output default --mode 2960x1848 2>/dev/null || true

    # Start openbox window manager so RuneLite is maximized to fill the display.
    mkdir -p /root/.config/openbox
    cat > /root/.config/openbox/rc.xml << 'OBCFG'
<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<openbox_config xmlns=\"http://openbox.org/3.4/rc\"
    xmlns:xi=\"http://www.w3.org/2001/XInclude\">
  <applications>
    <application class=\"*\" groupclass=\"*\">
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
    REPO_DIR=\"/root/.runelite/repository2\"
    if [ -d \"\$REPO_DIR\" ] && ls \"\$REPO_DIR\"/*.jar > /dev/null 2>&1; then
        CP=\$(echo \"\$REPO_DIR\"/*.jar | tr ' ' ':')
        echo 'Running RuneLite client directly (classpath found)' >&2
        exec java -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
            -Dsun.java2d.opengl=false \
            -Dsun.java2d.uiScale=2.0 \
            -Drunelite.launcher.version=2.7.6 \
            -cp \"\$CP\" \
            net.runelite.client.RuneLite --insecure-write-credentials
    else
        echo 'No client jars found — running launcher to download them first' >&2
        java -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
            -Dsun.java2d.uiScale=2.0 \
            -jar RuneLite.jar --insecure-write-credentials
    fi
" 2>&1 | tee -a "$LOGFILE"

# PIPESTATUS[0] captures proot-distro exit code, not tee's exit code.
EXIT_CODE=${PIPESTATUS[0]}
echo "RuneLite exited with code: $EXIT_CODE" | tee -a "$LOGFILE"

# Clean up any lingering credential files
rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true

if [ $EXIT_CODE -ne 0 ]; then
    echo "" | tee -a "$LOGFILE"
    echo "RuneLite failed. Log saved to: $LOGFILE" | tee -a "$LOGFILE"
    echo "Press Enter to exit..."
    [ -t 0 ] && read -r || sleep 5
fi

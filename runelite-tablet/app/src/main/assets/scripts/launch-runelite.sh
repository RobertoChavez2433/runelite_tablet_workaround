#!/data/data/com.termux/files/usr/bin/bash
# No set -e — we want to see ALL errors, not exit on the first one
set -uo pipefail

LOGFILE="$HOME/runelite-launch.log"
# Termux uses $PREFIX/tmp, not /tmp — resolve the actual X11 socket path
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
# Initialize PIDs for cleanup trap (set -u requires these to exist)
X11_PID=""
PERF_MONITOR_PID=""
echo "=== RuneLite launch $(date) ===" | tee "$LOGFILE"

# ===================================================================
# Clean shutdown of any previous session
# ===================================================================
# Kill everything from a prior run so we start fresh every time.
# This prevents zombie processes when the Android app restarts us.
cleanup_previous() {
    echo "Cleaning up previous session..." | tee -a "$LOGFILE"
    local killed=0

    # Kill Java (RuneLite) inside proot — match the main class or jar
    for pid in $(pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null) \
               $(pgrep -f 'RuneLite.jar' 2>/dev/null); do
        kill "$pid" 2>/dev/null && killed=$((killed+1))
    done

    # Kill openbox window manager
    pkill -f 'openbox' 2>/dev/null && killed=$((killed+1))

    # Kill any lingering proot-distro sessions
    pkill -f 'proot-distro' 2>/dev/null && killed=$((killed+1))
    pkill -f 'proot --' 2>/dev/null && killed=$((killed+1))

    # Kill PulseAudio (we'll restart it fresh)
    pulseaudio --kill 2>/dev/null && killed=$((killed+1))

    # Kill previous termux-x11 server process
    pkill -f 'termux-x11' 2>/dev/null && killed=$((killed+1))

    # Kill previous perf monitor if still running
    pkill -f 'perf-monitor.log' 2>/dev/null && killed=$((killed+1))

    # Clean up stale credential and PID files
    rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
    rm -f "$HOME/.rlt-launch-env.sh" 2>/dev/null || true

    # Brief pause to let processes die
    if [ "$killed" -gt 0 ]; then
        echo "  Killed $killed leftover process(es), waiting for cleanup..." | tee -a "$LOGFILE"
        sleep 1
    else
        echo "  No leftover processes found" | tee -a "$LOGFILE"
    fi
}

cleanup_previous

# ===================================================================
# EXIT trap — comprehensive cleanup when this session ends
# ===================================================================
cleanup_on_exit() {
    echo "" | tee -a "$LOGFILE"
    echo "=== Shutting down RuneLite session $(date) ===" | tee -a "$LOGFILE"

    # Kill perf monitor
    [ -n "${PERF_MONITOR_PID:-}" ] && kill "$PERF_MONITOR_PID" 2>/dev/null

    # Kill Java/RuneLite
    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
    pkill -f 'RuneLite.jar' 2>/dev/null || true

    # Kill openbox
    pkill -f 'openbox' 2>/dev/null || true

    # Kill proot sessions
    pkill -f 'proot-distro' 2>/dev/null || true
    pkill -f 'proot --' 2>/dev/null || true

    # Stop PulseAudio
    pulseaudio --kill 2>/dev/null || true

    # Kill X11 server
    [ -n "${X11_PID:-}" ] && kill "$X11_PID" 2>/dev/null

    # Send stop broadcast to Termux:X11 Android app
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true

    # Clean up credential and PID files
    rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
    rm -f "$HOME/.rlt-launch-env.sh" 2>/dev/null || true

    echo "Shutdown complete" | tee -a "$LOGFILE"
}

trap cleanup_on_exit EXIT

# ===================================================================
# Credential injection
# ===================================================================
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

# ===================================================================
# Start services
# ===================================================================
# Start PulseAudio for game audio
echo "Starting PulseAudio..." | tee -a "$LOGFILE"
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
    --exit-idle-time=-1 2>&1 | tee -a "$LOGFILE" || true

# Start Termux:X11
echo "Starting Termux:X11..." | tee -a "$LOGFILE"
termux-x11 :0 &
X11_PID=$!

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
# Bind-mount GPU device node for hardware acceleration (silent failure if unavailable).
GPU_BIND=""
if [ -e /dev/kgsl-3d0 ]; then
    GPU_BIND="--bind /dev/kgsl-3d0:/dev/kgsl-3d0"
    echo "GPU device node found — binding /dev/kgsl-3d0" | tee -a "$LOGFILE"
else
    echo "GPU device node not found — software rendering only" | tee -a "$LOGFILE"
fi
echo "Launching RuneLite..." | tee -a "$LOGFILE"
proot-distro login ubuntu --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix" --bind "$PREFIX/tmp:/tmp" $GPU_BIND -- bash -c "
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

    # ---------------------------------------------------------------
    # GPU acceleration detection (Zink + Turnip)
    # ---------------------------------------------------------------
    GPU_AVAILABLE=false
    if [ -e /dev/kgsl-3d0 ]; then
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export GALLIUM_DRIVER=zink
        export TU_DEBUG=noconform
        export MESA_NO_ERROR=1
        export ZINK_DESCRIPTORS=lazy

        GL_RENDERER=\$(glxinfo 2>/dev/null | grep -i 'opengl renderer' || true)
        if echo \"\$GL_RENDERER\" | grep -qi 'zink'; then
            GPU_AVAILABLE=true
            echo 'GPU acceleration: ENABLED (Zink+Turnip)' >&2
        else
            echo 'GPU acceleration: FAILED (falling back to software)' >&2
            unset MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER TU_DEBUG MESA_NO_ERROR ZINK_DESCRIPTORS
        fi
    else
        echo 'GPU acceleration: UNAVAILABLE (no /dev/kgsl-3d0)' >&2
    fi

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

    # --- Performance monitoring (background) ---
    PERF_LOG=\"/root/runelite/perf-monitor.log\"
    echo '=== Performance monitor started ===' > \"\$PERF_LOG\"
    (
        while true; do
            echo \"--- \$(date +%H:%M:%S) ---\" >> \"\$PERF_LOG\"
            # Memory: total/used/free in MB
            free -m | head -2 >> \"\$PERF_LOG\"
            # CPU load averages
            cat /proc/loadavg >> \"\$PERF_LOG\"
            # Top 5 CPU-consuming processes
            ps aux --sort=-%cpu 2>/dev/null | head -6 >> \"\$PERF_LOG\" || true
            echo '' >> \"\$PERF_LOG\"
            sleep 5
        done
    ) &
    PERF_PID=\$!

    # Report display and GL info before launch
    echo '=== Display info ===' | tee -a \"\$PERF_LOG\"
    xrandr 2>&1 | tee -a \"\$PERF_LOG\" || true
    echo '=== GL info ===' | tee -a \"\$PERF_LOG\"
    glxinfo 2>/dev/null | grep -iE '(renderer|version|vendor|direct)' | tee -a \"\$PERF_LOG\" || true

    # GC logging flags (OpenJDK 11 unified logging)
    GC_LOG_FLAGS=\"-Xlog:gc*:file=/root/runelite/gc.log:time,uptime,level,tags:filecount=2,filesize=5M\"

    # Conditional JVM flags based on GPU availability
    if [ \"\$GPU_AVAILABLE\" = true ]; then
        GPU_FLAGS=''
        echo 'Using GPU-accelerated rendering' >&2
    else
        GPU_FLAGS='-Dsun.java2d.opengl=false'
        echo 'Using software rendering' >&2
    fi

    # Build classpath from repository2 jars downloaded by the launcher
    REPO_DIR=\"/root/.runelite/repository2\"
    if [ -d \"\$REPO_DIR\" ] && ls \"\$REPO_DIR\"/*.jar > /dev/null 2>&1; then
        CP=\$(echo \"\$REPO_DIR\"/*.jar | tr ' ' ':')
        echo 'Running RuneLite client directly (classpath found)' >&2
        java -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
            \$GC_LOG_FLAGS \
            \$GPU_FLAGS \
            -Dsun.java2d.uiScale=2.0 \
            -Drunelite.launcher.version=2.7.6 \
            -cp \"\$CP\" \
            net.runelite.client.RuneLite --insecure-write-credentials --debug &
        JAVA_PID=\$!
    else
        echo 'No client jars found — running launcher to download them first' >&2
        java -Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
            \$GC_LOG_FLAGS \
            \$GPU_FLAGS \
            -Dsun.java2d.uiScale=2.0 \
            -jar RuneLite.jar --insecure-write-credentials --debug &
        JAVA_PID=\$!
    fi

    # Write PID file for health monitoring (read by SessionHealthMonitor)
    echo \"\$JAVA_PID\" > /root/.rlt-session.pid
    echo \"RuneLite started with PID \$JAVA_PID\" >&2

    # Wait for java to exit
    wait \$JAVA_PID
    JAVA_EXIT=\$?
    rm -f /root/.rlt-session.pid

    # Clean up perf monitor
    kill \$PERF_PID 2>/dev/null || true
    exit \$JAVA_EXIT
" 2>&1 | tee -a "$LOGFILE"

# PIPESTATUS[0] captures proot-distro exit code, not tee's exit code.
EXIT_CODE=${PIPESTATUS[0]}
echo "RuneLite exited with code: $EXIT_CODE" | tee -a "$LOGFILE"

if [ $EXIT_CODE -ne 0 ]; then
    echo "" | tee -a "$LOGFILE"
    echo "RuneLite failed. Log saved to: $LOGFILE" | tee -a "$LOGFILE"
    echo "Press Enter to exit..."
    [ -t 0 ] && read -r || sleep 5
fi

# EXIT trap handles all cleanup — no need for manual cleanup here

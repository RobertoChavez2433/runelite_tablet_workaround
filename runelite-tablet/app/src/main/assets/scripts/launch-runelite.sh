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
# Credential injection — MUST happen before cleanup_previous because
# the old launch script's EXIT trap deletes .rlt-launch-env.sh
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
else
    echo "No credentials env file provided — RuneLite will show its own login" | tee -a "$LOGFILE"
fi

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
    # The actual X server binary is 'com.termux.x11.Loader', not 'termux-x11'
    pkill -f 'termux-x11' 2>/dev/null && killed=$((killed+1))
    pkill -f 'com.termux.x11.Loader' 2>/dev/null && killed=$((killed+1))

    # Kill previous perf monitor if still running
    pkill -f 'perf-monitor.log' 2>/dev/null && killed=$((killed+1))

    # Kill virgl server and clean up socket
    pkill -f 'virgl_test_server' 2>/dev/null && killed=$((killed+1))
    rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true

    # Clean up stale credential, PID, and sentinel files
    # NOTE: Do NOT delete $HOME/.rlt-launch-env.sh here — it hasn't been read yet.
    # It gets cleaned up after sourcing (line 115) and in cleanup_on_exit().
    rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
    rm -f "$PREFIX/tmp/.rlt-session-alive" 2>/dev/null || true

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

    # Kill virgl server and clean up socket
    [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null
    pkill -f 'virgl_test_server' 2>/dev/null || true
    rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true

    # Kill X11 server (binary is 'com.termux.x11.Loader', not 'termux-x11')
    [ -n "${X11_PID:-}" ] && kill "$X11_PID" 2>/dev/null
    pkill -f 'com.termux.x11.Loader' 2>/dev/null || true

    # Send stop broadcast to Termux:X11 Android app
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true

    # Clean up credential, PID, and sentinel files
    # NOTE: Do NOT delete $HOME/.rlt-launch-env.sh here — a new launch may have
    # deployed one already. It's cleaned up after sourcing at the top of this script.
    rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
    rm -f "$PREFIX/tmp/.rlt-session-alive" 2>/dev/null || true

    echo "Shutdown complete" | tee -a "$LOGFILE"
}

trap cleanup_on_exit EXIT

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
# Syntax: termux-x11-preference key:value (colon separator, NOT equals)
echo "Setting Termux:X11 preferences (shell backup)..." | tee -a "$LOGFILE"
timeout 5 termux-x11-preference fullscreen:true 2>&1 | tee -a "$LOGFILE" || true
timeout 5 termux-x11-preference showAdditionalKbd:false 2>&1 | tee -a "$LOGFILE" || true

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
    } > "$TERMUX_ENV_FILE"
    chmod 600 "$TERMUX_ENV_FILE"
    echo "Credential env file written for proot forwarding" | tee -a "$LOGFILE"
fi

# ===================================================================
# GPU acceleration setup
# ===================================================================
SCRIPTS_DIR="$HOME/scripts"
GPU_VENDOR=$("$SCRIPTS_DIR/detect-gpu.sh" 2>/dev/null || echo "unknown")
echo "GPU vendor detected: $GPU_VENDOR" | tee -a "$LOGFILE"

GPU_BIND=""
VIRGL_PID=""
PROOT_GPU_ENV=""

if [ "$GPU_VENDOR" = "adreno" ]; then
    # Adreno: bind kgsl device node for Zink+Turnip
    if [ -e /dev/kgsl-3d0 ]; then
        GPU_BIND="--bind /dev/kgsl-3d0:/dev/kgsl-3d0"
        PROOT_GPU_ENV="adreno"
        echo "Adreno GPU: binding /dev/kgsl-3d0" | tee -a "$LOGFILE"
    fi
elif [ "$GPU_VENDOR" = "mali" ]; then
    # Mali: start VirGL server (ANGLE backend first, then native GLES fallback)
    pkill -f virgl_test_server 2>/dev/null || true
    sleep 0.5

    if command -v virgl_test_server_android >/dev/null 2>&1; then
        VIRGL_SOCKET="$PREFIX/tmp/.virgl_test"

        # Tier 1: VirGL + ANGLE (Vulkan backend)
        echo "Starting VirGL+ANGLE server..." | tee -a "$LOGFILE"
        env -u LD_LIBRARY_PATH virgl_test_server_android --angle-gl &
        VIRGL_PID=$!

        # Wait for server to be ready (PID alive AND socket exists)
        VIRGL_READY=false
        for i in $(seq 1 30); do
            if kill -0 $VIRGL_PID 2>/dev/null && [ -S "$VIRGL_SOCKET" ]; then
                VIRGL_READY=true
                break
            fi
            sleep 0.2
        done

        if [ "$VIRGL_READY" = true ]; then
            echo "VirGL+ANGLE server started (PID $VIRGL_PID, socket ready)" | tee -a "$LOGFILE"
            PROOT_GPU_ENV="mali-angle"
        else
            echo "VirGL+ANGLE failed (socket: $(ls -la "$VIRGL_SOCKET" 2>&1)), trying native GLES..." | tee -a "$LOGFILE"
            kill $VIRGL_PID 2>/dev/null || true
            rm -f "$VIRGL_SOCKET" 2>/dev/null || true

            # Tier 2: VirGL + native GLES
            env -u LD_LIBRARY_PATH virgl_test_server_android &
            VIRGL_PID=$!

            VIRGL_READY=false
            for i in $(seq 1 30); do
                if kill -0 $VIRGL_PID 2>/dev/null && [ -S "$VIRGL_SOCKET" ]; then
                    VIRGL_READY=true
                    break
                fi
                sleep 0.2
            done

            if [ "$VIRGL_READY" = true ]; then
                echo "VirGL native GLES server started (PID $VIRGL_PID, socket ready)" | tee -a "$LOGFILE"
                PROOT_GPU_ENV="mali-native"
            else
                echo "VirGL unavailable (socket: $(ls -la "$VIRGL_SOCKET" 2>&1)), using software rendering" | tee -a "$LOGFILE"
                kill $VIRGL_PID 2>/dev/null || true
                VIRGL_PID=""
                PROOT_GPU_ENV=""
            fi
        fi
    else
        echo "virgl_test_server_android not found — software rendering" | tee -a "$LOGFILE"
    fi
else
    echo "Unknown GPU vendor ($GPU_VENDOR) — software rendering" | tee -a "$LOGFILE"
fi

# Launch RuneLite inside proot.
# Bind-mount Termux's X11 socket directory into proot so DISPLAY=:0 can find it.
# Bind-mount GPU device node for hardware acceleration (silent failure if unavailable).
# Create sentinel file for health monitoring (SessionHealthMonitor checks this)
# $PREFIX/tmp is bind-mounted into proot as /tmp, so both sides can see it.
touch "$PREFIX/tmp/.rlt-session-alive"
echo "Session sentinel created" | tee -a "$LOGFILE"

# Re-check VirGL server is still alive (it may have crashed after initial health check)
if [ -n "$VIRGL_PID" ] && ! kill -0 "$VIRGL_PID" 2>/dev/null; then
    echo "WARNING: VirGL server died before launch — falling back to software rendering" | tee -a "$LOGFILE"
    VIRGL_PID=""
    PROOT_GPU_ENV=""
fi

# Set display resolution via Termux:X11 preferences (xrandr doesn't work — no transform support).
# GPU path: native resolution. Software path: half-res for 4x fewer pixels.
if [ -n "$PROOT_GPU_ENV" ]; then
    echo "Setting display: native resolution (GPU available)" | tee -a "$LOGFILE"
    timeout 5 termux-x11-preference displayResolutionMode:native 2>&1 | tee -a "$LOGFILE" || true
else
    echo "Setting display: 1480x924 (software rendering, half-res)" | tee -a "$LOGFILE"
    timeout 5 termux-x11-preference displayResolutionMode:custom displayResolutionCustom:1480x924 2>&1 | tee -a "$LOGFILE" || true
fi

echo "Launching RuneLite..." | tee -a "$LOGFILE"
echo "VirGL socket: $(ls -la "$PREFIX/tmp/.virgl_test" 2>&1)" | tee -a "$LOGFILE"
proot-distro login ubuntu --shared-tmp $GPU_BIND -- bash -c "
    export DISPLAY=:0
    export PULSE_SERVER=tcp:127.0.0.1:4713

    # Source credentials if available (Spike C: must source INSIDE proot)
    if [ -n '${PROOT_ENV_FILE}' ] && [ -f '${PROOT_ENV_FILE}' ]; then
        source '${PROOT_ENV_FILE}'
        rm -f '${PROOT_ENV_FILE}'
    fi

    # Brief delay for X11 to initialize before GPU detection and xrandr
    sleep 0.5

    # Skip errors for all Mesa operations (applies to both GPU and software paths)
    export MESA_NO_ERROR=1

    # ---------------------------------------------------------------
    # GPU acceleration detection
    # ---------------------------------------------------------------
    GPU_AVAILABLE=false
    PROOT_GPU_ENV='${PROOT_GPU_ENV}'

    if [ \"\$PROOT_GPU_ENV\" = \"adreno\" ]; then
        # Adreno: Zink + Turnip
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export GALLIUM_DRIVER=zink
        export TU_DEBUG=noconform
        export MESA_NO_ERROR=1
        export ZINK_DESCRIPTORS=lazy

        GL_RENDERER=\$(glxinfo 2>/dev/null | grep -i \"opengl renderer\" || true)
        if echo \"\$GL_RENDERER\" | grep -qi \"zink\"; then
            GPU_AVAILABLE=true
            echo \"GPU acceleration: ENABLED (Zink+Turnip)\" >&2
        else
            echo \"GPU acceleration: Zink failed, falling back to software\" >&2
            unset MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER TU_DEBUG MESA_NO_ERROR ZINK_DESCRIPTORS
        fi

    elif [ \"\$PROOT_GPU_ENV\" = \"mali-angle\" ] || [ \"\$PROOT_GPU_ENV\" = \"mali-native\" ]; then
        # Mali: VirGL — force 24-bit visual to avoid BadMatch on XGetImage (depth mismatch)
        export GALLIUM_DRIVER=virpipe
        export VTEST_SOCKET_NAME=/tmp/.virgl_test
        export MESA_GLX_ALPHA_BITS=0
        export MESA_NO_ERROR=1

        echo \"VirGL socket visible: \$(ls -la /tmp/.virgl_test 2>&1)\" >&2

        # glxinfo crashes with BadMatch on XGetImage (32-bit vs 24-bit visual mismatch).
        # Use glxgears instead — it uses XPutImage (write), not XGetImage (read).
        GEARS_OUTPUT=\$(timeout 3 glxgears -info 2>&1 || true)
        GL_RENDERER=\$(echo \"\$GEARS_OUTPUT\" | grep -i \"GL_RENDERER\" | head -1 || true)
        echo \"VirGL glxgears check: \$GL_RENDERER\" >&2

        if echo \"\$GL_RENDERER\" | grep -qi \"virgl\"; then
            OVERRIDE=\"4.5COMPAT\"
            [ \"\$PROOT_GPU_ENV\" = \"mali-native\" ] && OVERRIDE=\"3.1COMPAT\"
            export MESA_GL_VERSION_OVERRIDE=\"\$OVERRIDE\"
            export MESA_GLSL_VERSION_OVERRIDE=330
            GPU_AVAILABLE=true
            echo \"GPU acceleration: ENABLED (VirGL, GL=\$OVERRIDE GLSL=330)\" >&2
        else
            echo \"GPU acceleration: VirGL not detected (\$GL_RENDERER), falling back to software\" >&2
            unset GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_GLX_ALPHA_BITS MESA_NO_ERROR
        fi
    else
        echo \"GPU acceleration: UNAVAILABLE (software rendering)\" >&2
    fi

    # Resolution is set via Termux:X11 preferences (outside proot, before launch).
    # xrandr --newmode/--scale don't work — Termux:X11 doesn't implement RandR transforms.

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
    # Always --scale 2 for readable UI on high-DPI tablet display
    SCALE_FLAG='--scale 2'
    if [ \"\$GPU_AVAILABLE\" = true ]; then
        GPU_FLAGS=''
        echo 'Using GPU-accelerated rendering' >&2
    else
        GPU_FLAGS='-Dsun.java2d.opengl=false -Dsun.java2d.xrender=true -Dsun.java2d.pmoffscreen=true'
        echo 'Using software rendering (half-res for performance)' >&2
    fi

    # Always run through the launcher — it handles client jar auto-updates.
    # Bypassing the launcher freezes client jars at their initial version,
    # causing silent login failures when OSRS updates weekly.
    #
    # JVM flags must be passed via RUNELITE_VMARGS (env var read by Launcher.java)
    # because the launcher spawns the client as a CHILD process — flags on the
    # launcher java command only apply to the launcher, not the client.
    # RUNELITE_VMARGS is appended AFTER bootstrap's -Xmx768m, so last-Xmx wins.
    # --scale controls -Dsun.java2d.uiScale passed to client (2 for GPU, 1 for software).
    export RUNELITE_VMARGS=\"-Xmx4g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \$GC_LOG_FLAGS \$GPU_FLAGS\"
    echo \"RUNELITE_VMARGS=\$RUNELITE_VMARGS\" >&2
    echo \"Running RuneLite via launcher (scale=\$SCALE_FLAG)\" >&2
    java -jar RuneLite.jar \$SCALE_FLAG --insecure-write-credentials --debug &
    JAVA_PID=\$!

    # Write PID file for health monitoring (read by SessionHealthMonitor)
    echo \"\$JAVA_PID\" > /root/.rlt-session.pid
    echo \"RuneLite started with PID \$JAVA_PID\" >&2

    # Wait for java to exit
    wait \$JAVA_PID
    JAVA_EXIT=\$?
    rm -f /root/.rlt-session.pid
    rm -f /tmp/.rlt-session-alive

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

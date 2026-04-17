#!/data/data/com.termux/files/usr/bin/bash
# launch-runelite-native.sh — Phase 2.1 native-Termux RuneLite launcher.
#
# This is the proot-free path. It bypasses proot-distro entirely, runs the
# JVM directly under Termux-native openjdk-21, and uses our Bionic-rebuilt
# rlawt-1.8-bionic.jar (see Task 21 spec).
#
# Goal: eliminate proot's ptrace syscall-interception (S77 diagnosis) so the
# RuneLite Client thread is no longer capped at ~2000 syscalls/s.
#
# Gated by RLT_NATIVE_TERMUX=1. If the env flag is not set, this script
# refuses to run so it can coexist with launch-runelite.sh during Phase 2.2
# service-selector wiring.
#
# Phase 2.1 exit: JVM starts under Termux-native openjdk-21 without proot,
# loads net.runelite.client.RuneLite, and Bionic dlopen resolves librlawt.so
# NEEDED libs (libjawt, libGL.so.1, libGLX.so.0, libX11.so, libm, libdl, libc).
# See spec .claude/specs/2026-04-17-phase-2.1-native-launch-spec.md.

set -uo pipefail

# ===================================================================
# Gate + env bootstrap
# ===================================================================
if [ "${RLT_NATIVE_TERMUX:-0}" != "1" ]; then
    echo "ERROR: launch-runelite-native.sh requires RLT_NATIVE_TERMUX=1" >&2
    echo "       This is the proot-free path. Use launch-runelite.sh for the proot path." >&2
    exit 2
fi

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -f "$TERMUX_PREFIX/etc/profile" ]; then
    # shellcheck disable=SC1091
    . "$TERMUX_PREFIX/etc/profile"
fi
export PREFIX="${PREFIX:-$TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$TMPDIR"

LOGFILE="$HOME/runelite-native.log"
CLASSLOAD_LOG="$HOME/native-launch-classload.log"
echo "=== Native RuneLite launch $(date) ===" | tee "$LOGFILE"

# Optional env-file argument (parity with launch-runelite.sh). LaunchCoordinator
# writes Jagex auth env to $HOME/.rlt-launch-env.sh and passes its path as $1.
# We source it and then delete it to avoid leaving credentials on disk.
NATIVE_ENV_FILE="${1:-}"
if [ -n "$NATIVE_ENV_FILE" ] && [ -f "$NATIVE_ENV_FILE" ]; then
    echo "Sourcing credentials from env file..." | tee -a "$LOGFILE"
    # shellcheck disable=SC1090
    source "$NATIVE_ENV_FILE"
    rm -f "$NATIVE_ENV_FILE"
    [ -n "${JX_SESSION_ID:-}" ] && echo "  JX_SESSION_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_CHARACTER_ID:-}" ] && echo "  JX_CHARACTER_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_DISPLAY_NAME:-}" ] && echo "  JX_DISPLAY_NAME=***" | tee -a "$LOGFILE"
else
    echo "No credentials env file provided — RuneLite will show its own login" | tee -a "$LOGFILE"
fi

# Same cpuset-at-entry logging as the proot launcher, so we can diff.
rlt_log_cpuset() {
    local label="$1" pid="$2"
    local file="/proc/$pid/cpuset"
    local cset
    cset=$(cat "$file" 2>/dev/null || echo "<unreadable>")
    local line="CPUSET $label pid=$pid cpuset=$cset"
    echo "$line" | tee -a "$LOGFILE" 2>/dev/null || true
    if [ -x /system/bin/log ]; then
        /system/bin/log -t RLT -p i "$line" 2>/dev/null || true
    fi
}
rlt_log_cpuset "native-launch-entry" "$$"

# ===================================================================
# Locking (distinct from proot launcher so both paths can coexist)
# ===================================================================
LAUNCH_LOCK_DIR="$PREFIX/tmp/.rlt-launch-native.lock"
LAUNCH_LOCK_PID_FILE="$LAUNCH_LOCK_DIR/pid"

acquire_launch_lock() {
    if mkdir "$LAUNCH_LOCK_DIR" 2>/dev/null; then
        echo "$$" > "$LAUNCH_LOCK_PID_FILE"
        return 0
    fi
    if [ -f "$LAUNCH_LOCK_PID_FILE" ]; then
        local existing_pid
        existing_pid="$(cat "$LAUNCH_LOCK_PID_FILE" 2>/dev/null || true)"
        if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
            echo "Another native RuneLite launch is already in progress (PID $existing_pid)" | tee -a "$LOGFILE"
            exit 0
        fi
    fi
    rm -rf "$LAUNCH_LOCK_DIR" 2>/dev/null || true
    mkdir "$LAUNCH_LOCK_DIR" 2>/dev/null && echo "$$" > "$LAUNCH_LOCK_PID_FILE"
}
release_launch_lock() {
    rm -rf "$LAUNCH_LOCK_DIR" 2>/dev/null || true
}

acquire_launch_lock

# ===================================================================
# Paths & constants
# ===================================================================
ROOTFS_PATH="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
RL_DOT_DIR="$ROOTFS_PATH/root/.runelite"
RL_REPO_DIR="$RL_DOT_DIR/repository2"
RL_PROFILE_DIR="$RL_DOT_DIR/profiles2"
RL_CLIENT_LOG="$RL_DOT_DIR/logs/client.log"
# APK-deployed location for the Bionic rlawt jar.
# Set RLT_RLAWT_BIONIC_JAR to override (e.g. during ad-hoc adb push testing).
RLAWT_BIONIC_JAR="${RLT_RLAWT_BIONIC_JAR:-$HOME/.rlt/rlawt-1.8-bionic.jar}"

JAVA_HOME_NATIVE="$PREFIX/lib/jvm/java-21-openjdk"
JAVA_BIN="$JAVA_HOME_NATIVE/bin/java"
# Bionic dlopen does not honor -Djava.library.path for resolving NEEDED libs.
# This is the runtime requirement from Task 21 Review Note #4.
NATIVE_LD_LIBRARY_PATH="$JAVA_HOME_NATIVE/lib:$PREFIX/lib"

export TERMUX_X11_BIN="$PREFIX/bin/termux-x11"
export TERMUX_X11_PREF_BIN="$PREFIX/bin/termux-x11-preference"
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
X11_HOST_COMPONENT="com.runelitetablet/.presentation.hybrid.HybridX11HostActivity"
X11_OVERRIDE_PACKAGE="com.runelitetablet"
export TERMUX_X11_FORCE_FLIP=1

# PIDs we own (for EXIT trap)
VIRGL_PID=""
X11_PID=""
JAVA_PID=""

# ===================================================================
# Cleanup
# ===================================================================
find_pids_by_cmdline() {
    local pattern="$1" proc cmdline
    for proc in /proc/[0-9]*; do
        [ -r "$proc/cmdline" ] || continue
        cmdline="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
        case "$cmdline" in
            *"$pattern"*) basename "$proc" ;;
        esac
    done
}

stop_termux_x11() {
    local pid
    for pid in $(find_pids_by_cmdline 'com.termux.x11.Loader') \
               $(find_pids_by_cmdline 'com.termux.x11.CmdEntryPoint') \
               $(find_pids_by_cmdline 'termux-x11'); do
        kill "$pid" 2>/dev/null || true
    done
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true
    rm -f "$TMPDIR/.tX0-lock" 2>/dev/null || true
    rm -f "$TMPDIR/.X0-lock" 2>/dev/null || true
    rm -f "$X11_SOCKET_DIR/X0" 2>/dev/null || true
}

cleanup_previous() {
    echo "Cleaning up previous native-launch state..." | tee -a "$LOGFILE"
    pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null | xargs -r kill 2>/dev/null || true
    # Don't kill virgl if the external-virgl sentinel is active (we'd adopt it below).
    if [ ! -f "$HOME/.rlt-external-virgl" ]; then
        pkill -f 'virgl_test_server' 2>/dev/null || true
        rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true
    fi
    stop_termux_x11
    sleep 0.5
}

cleanup_on_exit() {
    echo "" | tee -a "$LOGFILE"
    echo "=== Native launch exit $(date) ===" | tee -a "$LOGFILE"
    [ -n "${JAVA_PID:-}" ] && kill "$JAVA_PID" 2>/dev/null || true
    [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null || true
    [ -n "${X11_PID:-}" ] && kill "$X11_PID" 2>/dev/null || true
    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
    pkill -f 'virgl_test_server' 2>/dev/null || true
    stop_termux_x11
    rm -f "$HOME/.rlt-native.pid" 2>/dev/null || true
    release_launch_lock
    echo "Native launch cleanup complete" | tee -a "$LOGFILE"
}
trap cleanup_on_exit EXIT

cleanup_previous

# ===================================================================
# Preflight — fail fast with specific error messages
# ===================================================================
if [ ! -x "$JAVA_BIN" ]; then
    echo "ERROR: Termux openjdk-21 missing at $JAVA_BIN" | tee -a "$LOGFILE"
    echo "       Install with: pkg install openjdk-21" | tee -a "$LOGFILE"
    exit 1
fi
if [ ! -f "$RLAWT_BIONIC_JAR" ]; then
    echo "ERROR: Bionic rlawt jar missing at $RLAWT_BIONIC_JAR" | tee -a "$LOGFILE"
    echo "       Deploy runelite-tablet/app/libs/rlawt-1.8-bionic.jar to \$HOME/.rlt/ before running." | tee -a "$LOGFILE"
    exit 1
fi
if [ ! -d "$RL_REPO_DIR" ]; then
    echo "ERROR: RuneLite repository2 dir missing at $RL_REPO_DIR" | tee -a "$LOGFILE"
    echo "       Run the proot launcher at least once to populate it, or restore .runelite from backup." | tee -a "$LOGFILE"
    exit 1
fi

echo "Preflight OK:" | tee -a "$LOGFILE"
echo "  java=$JAVA_BIN" | tee -a "$LOGFILE"
echo "  rlawt-bionic-jar=$RLAWT_BIONIC_JAR ($(stat -c%s "$RLAWT_BIONIC_JAR" 2>/dev/null) bytes)" | tee -a "$LOGFILE"
echo "  rl-repo=$RL_REPO_DIR ($(ls "$RL_REPO_DIR"/*.jar 2>/dev/null | wc -l) jars)" | tee -a "$LOGFILE"

# ===================================================================
# Start PulseAudio (minimal — RL needs an audio endpoint or it logs noisily)
# ===================================================================
if command -v pulseaudio >/dev/null 2>&1; then
    pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
        --exit-idle-time=-1 2>&1 | tee -a "$LOGFILE" || true
    export PULSE_SERVER=tcp:127.0.0.1:4713
else
    echo "PulseAudio not installed — RL will use null audio backend" | tee -a "$LOGFILE"
    export PULSE_SERVER=""
fi

# ===================================================================
# Start Termux:X11 (identical to proot launcher)
# ===================================================================
if [ ! -x "$TERMUX_X11_BIN" ]; then
    echo "ERROR: Termux:X11 launcher missing at $TERMUX_X11_BIN" | tee -a "$LOGFILE"
    exit 1
fi
export TERMUX_X11_OVERRIDE_PACKAGE="$X11_OVERRIDE_PACKAGE"
mkdir -p "$X11_SOCKET_DIR"

# IMPORTANT: the Termux:X11 server reads its resolution pref ONCE at startup; changing
# `displayResolutionMode` after the server is up does nothing. A prior proot-software
# run (which sets 1480x924 half-res) leaves the custom-mode pref behind, so the native
# launcher MUST reset to `native` BEFORE forking the X server. Otherwise RuneLite's
# AWT screen bounds report 1480x924 and the whole frame renders small.
# `adjustResolution:true` asks Termux:X11 to resize the X canvas to the surface size
# whenever the activity's window changes — without it, `native` mode falls back to a
# DP-based half-res on HiDPI displays (2960×1848 physical ⇒ 1480×924 DP).
if [ -x "$TERMUX_X11_PREF_BIN" ]; then
    timeout 5 "$TERMUX_X11_PREF_BIN" displayResolutionMode:native 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" adjustResolution:true 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" fullscreen:true 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" showAdditionalKbd:false 2>&1 | tee -a "$LOGFILE" || true
fi

"$TERMUX_X11_BIN" :0 &
X11_PID=$!
echo "X11 launch pid=$X11_PID" | tee -a "$LOGFILE"

X11_READY=false
for _i in $(seq 1 60); do
    if [ -e "$X11_SOCKET_DIR/X0" ]; then X11_READY=true; break; fi
    if ! kill -0 "$X11_PID" 2>/dev/null; then
        echo "X11 process $X11_PID died before socket creation" | tee -a "$LOGFILE"
        break
    fi
    sleep 0.2
done
if [ "$X11_READY" != true ]; then
    echo "ERROR: X11 socket not ready after 12s" | tee -a "$LOGFILE"
    exit 1
fi
echo "X11 socket ready" | tee -a "$LOGFILE"
export DISPLAY=:0

# Launch hybrid X11 host activity (undecorated frame). Mirrors proot launcher.
# Resolution/fullscreen prefs already set above; nothing else to do here.
am start --activity-single-top --activity-clear-top -n "$X11_HOST_COMPONENT" >/dev/null 2>&1 || true

# ===================================================================
# Start VirGL server (Termux-native already — no change from proot path)
# ===================================================================
if command -v virgl_test_server_android >/dev/null 2>&1; then
    EXTERNAL_VIRGL_SENTINEL="$HOME/.rlt-external-virgl"
    if [ -f "$EXTERNAL_VIRGL_SENTINEL" ] && [ -S "$PREFIX/tmp/.virgl_test" ]; then
        EXISTING_VIRGL_PID=$(pgrep -f virgl_test_server_android | head -1 || true)
        if [ -n "$EXISTING_VIRGL_PID" ]; then
            VIRGL_PID="$EXISTING_VIRGL_PID"
            echo "VIRGL-EXTERNAL: adopting pre-spawned virgl PID=$VIRGL_PID" | tee -a "$LOGFILE"
        fi
    fi
    if [ -z "$VIRGL_PID" ]; then
        VIRGL_LOG="$HOME/virgl-server.log"
        env -u LD_LIBRARY_PATH VIRGL_RENDERER_THREAD=1 VIRGL_RENDERER_ASYNC=1 \
            virgl_test_server_android > "$VIRGL_LOG" 2>&1 &
        VIRGL_PID=$!
        echo "VirGL server forked PID=$VIRGL_PID log=$VIRGL_LOG" | tee -a "$LOGFILE"
        for _i in $(seq 1 30); do
            [ -S "$PREFIX/tmp/.virgl_test" ] && kill -0 "$VIRGL_PID" 2>/dev/null && break
            sleep 0.2
        done
        if [ ! -S "$PREFIX/tmp/.virgl_test" ]; then
            echo "WARNING: VirGL socket not ready; rendering will fall back to software" | tee -a "$LOGFILE"
        else
            echo "VirGL socket ready" | tee -a "$LOGFILE"
        fi
    fi
    # Env for Mesa virpipe backend (mirrors proot launcher's post-validation block).
    # MESA_EXTENSION_OVERRIDE disables a depth-clamp pair that virgl's front-end
    # advertises but the Turnip/Mali driver behind it can't actually honor; leaving
    # them enabled causes RuneLite's GPU plugin to request features and then segfault.
    # The MESA_GLSL_CACHE path keeps the shader cache on-disk so cold-start re-compiles
    # are skipped between launches.
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME="$PREFIX/tmp/.virgl_test"
    export MESA_NO_ERROR=1
    export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
    export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    export MESA_GLSL_VERSION_OVERRIDE=430
    export MESA_GLSL_CACHE_DISABLE=0
    # Brief settle: virgl_test_server can accept the socket connection before its
    # internal FBConfig enumeration completes, which makes glXChooseFBConfig return
    # zero configs when RuneLite queries them (producing the "unable to find a fb
    # config" RuntimeException from rlawt). 500ms after socket-ready is enough on
    # the Samsung Tab S10 Ultra to let virgl finish initializing.
    sleep 0.5
else
    echo "virgl_test_server_android not installed — software rendering" | tee -a "$LOGFILE"
fi

# Export LD_LIBRARY_PATH + JAVA_HOME NOW so the DIAG block below captures real values
# (previously they were only set right before the JVM invoke, and the DIAG dump logged
# `<unset>`). glxinfo also needs LD_LIBRARY_PATH set to pick up Termux's libGL rather
# than any system stub.
export LD_LIBRARY_PATH="$NATIVE_LD_LIBRARY_PATH"
export JAVA_HOME="$JAVA_HOME_NATIVE"

# ===================================================================
# DIAGNOSTIC PREFLIGHT — dump everything rlawt/Java/virgl see so we can
# actually debug size/GPU issues without guessing. Adds no overhead in
# the happy path (log writes only) and makes post-mortems tractable.
# ===================================================================
echo "=== DIAG preflight @$(date +%H:%M:%S.%3N) ===" | tee -a "$LOGFILE"
echo "[DIAG] device physical size (Android wm): unreadable from run-as com.termux" | tee -a "$LOGFILE"
echo "[DIAG] Termux:X11 resolution prefs:" | tee -a "$LOGFILE"
if [ -r /data/data/com.termux.x11/shared_prefs/com.termux.x11_preferences.xml ]; then
    grep -E 'displayResolution|fullscreen|adjustResolution|displayStretch' \
        /data/data/com.termux.x11/shared_prefs/com.termux.x11_preferences.xml \
        2>/dev/null | sed 's/^/  /' | tee -a "$LOGFILE"
else
    echo "  (prefs file not readable from com.termux uid)" | tee -a "$LOGFILE"
fi
echo "[DIAG] Termux:X11 server process(es):" | tee -a "$LOGFILE"
# Termux:X11's server runs under Android app_process with its cmdline set to
# "com.termux.x11.Loader" or "com.termux.x11.CmdEntryPoint" — never "termux-x11".
# Match via find_pids_by_cmdline to cover both.
_x11_pids="$(find_pids_by_cmdline 'com.termux.x11.Loader')$(echo)$(find_pids_by_cmdline 'com.termux.x11.CmdEntryPoint')"
if [ -n "$_x11_pids" ]; then
    for p in $_x11_pids; do
        echo "  pid=$p cmdline=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)" | tee -a "$LOGFILE"
    done
else
    echo "  (none)" | tee -a "$LOGFILE"
fi
echo "[DIAG] VirGL server:" | tee -a "$LOGFILE"
if [ -S "$PREFIX/tmp/.virgl_test" ]; then
    echo "  socket OK $(stat -c '%Y %n' "$PREFIX/tmp/.virgl_test")" | tee -a "$LOGFILE"
    pgrep -af virgl_test_server_android 2>/dev/null | sed 's/^/  pid: /' | tee -a "$LOGFILE"
    echo "  virgl-server.log (last 5):" | tee -a "$LOGFILE"
    tail -5 "$HOME/virgl-server.log" 2>/dev/null | sed 's/^/    /' | tee -a "$LOGFILE"
else
    echo "  socket missing — virgl not running, RL will see software Mesa only" | tee -a "$LOGFILE"
fi
echo "[DIAG] Mesa env that rlawt/GL will see:" | tee -a "$LOGFILE"
for v in GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_GL_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE MESA_EXTENSION_OVERRIDE MESA_NO_ERROR LD_LIBRARY_PATH DISPLAY; do
    eval "val=\${$v:-<unset>}"
    echo "  $v=$val" | tee -a "$LOGFILE"
done
echo "[DIAG] glxinfo (OpenGL renderer + FBConfigs):" | tee -a "$LOGFILE"
if command -v glxinfo >/dev/null 2>&1; then
    # -B: short info (vendor / renderer / version); then dump up to 50 FBConfigs
    # so we can see what Mesa/virgl actually offers when rlawt's glXChooseFBConfig
    # returns 0 matches.
    timeout 10 glxinfo -B 2>&1 | head -30 | sed 's/^/  /' | tee -a "$LOGFILE"
    echo "[DIAG] glxinfo FBConfig summary (first 10 visuals):" | tee -a "$LOGFILE"
    timeout 10 glxinfo 2>&1 | grep -E 'visual id|color sizes|samples|stencil|depth|drawable|renderable' \
        | head -40 | sed 's/^/  /' | tee -a "$LOGFILE"
else
    echo "  glxinfo not installed — run 'pkg install mesa-demos' for fb-config diagnostics" | tee -a "$LOGFILE"
fi
echo "[DIAG] RL profile window/layout prefs:" | tee -a "$LOGFILE"
RL_PROFILE_DIR="$ROOTFS_PATH/root/.runelite/profiles2"
if [ -d "$RL_PROFILE_DIR" ]; then
    for prof in "$RL_PROFILE_DIR"/*.properties; do
        [ -f "$prof" ] || continue
        echo "  --- $(basename "$prof") ---" | tee -a "$LOGFILE"
        grep -aE 'runelite\.(gameSize|lockWindowSize|containInScreen|automaticResizeType|rememberScreenBounds)|stretchedmode\.' \
            "$prof" 2>/dev/null | sed 's/^/    /' | tee -a "$LOGFILE"
    done
else
    echo "  (profile dir missing)" | tee -a "$LOGFILE"
fi
echo "[DIAG] Bionic rlawt jar expected on classpath:" | tee -a "$LOGFILE"
echo "  $(ls -la "$RLAWT_BIONIC_JAR" 2>&1)" | tee -a "$LOGFILE"
echo "=== DIAG end ===" | tee -a "$LOGFILE"

rlt_log_cpuset "virgl-ready" "${VIRGL_PID:-$$}"

# ===================================================================
# Profile-patching: rewrite runelite.gameSize + enable RESIZE_WINDOW +
# enable stretched mode so the AWT Frame fills the Termux:X11 canvas
# instead of rendering at the last cached "postcard" size (typ 765×503).
#
# Values come from the Kotlin side — RLT_GAME_SIZE_W/H are computed from
# context.resources.displayMetrics / uiScale, since run-as com.termux
# can't query WindowManagerService. The shell must not hardcode sizes
# (see memory/feedback_no_hardcoded_ui_sizes.md).
#
# Backup is taken to .rlt-orig-native once (distinct from the proot
# probe's .rlt-orig suffix) so the two launch paths don't step on
# each other's backups.
# ===================================================================
if [ -d "$ROOTFS_PATH/root/.runelite/profiles2" ] \
   && [ -n "${RLT_GAME_SIZE_W:-}" ] \
   && [ -n "${RLT_GAME_SIZE_H:-}" ]; then
    for CFG in "$ROOTFS_PATH/root/.runelite/profiles2"/*.properties; do
        [ -f "$CFG" ] || continue
        case "$(basename "$CFG")" in
            *rsprofile*) continue ;;
        esac
        [ -f "$CFG.rlt-orig-native" ] || cp "$CFG" "$CFG.rlt-orig-native"
        if grep -q '^runelite\.gameSize=' "$CFG"; then
            sed -i "s/^runelite\.gameSize=.*/runelite.gameSize=${RLT_GAME_SIZE_W}x${RLT_GAME_SIZE_H}/" "$CFG"
        else
            printf 'runelite.gameSize=%dx%d\n' "$RLT_GAME_SIZE_W" "$RLT_GAME_SIZE_H" >> "$CFG"
        fi
        if grep -q '^runelite\.automaticResizeType=' "$CFG"; then
            sed -i 's/^runelite\.automaticResizeType=.*/runelite.automaticResizeType=RESIZE_WINDOW/' "$CFG"
        else
            echo 'runelite.automaticResizeType=RESIZE_WINDOW' >> "$CFG"
        fi
        if grep -q '^stretchedmode\.scalingFactor=' "$CFG"; then
            sed -i 's/^stretchedmode\.scalingFactor=.*/stretchedmode.scalingFactor=100/' "$CFG"
        else
            echo 'stretchedmode.scalingFactor=100' >> "$CFG"
        fi
        if grep -q '^stretchedmode\.keepAspectRatio=' "$CFG"; then
            sed -i 's/^stretchedmode\.keepAspectRatio=.*/stretchedmode.keepAspectRatio=true/' "$CFG"
        else
            echo 'stretchedmode.keepAspectRatio=true' >> "$CFG"
        fi
        echo "RL-CONFIG native: patched $(basename "$CFG") gameSize=${RLT_GAME_SIZE_W}x${RLT_GAME_SIZE_H} resize=RESIZE_WINDOW stretched=100 keepAspect=true" | tee -a "$LOGFILE"
    done
else
    if [ -z "${RLT_GAME_SIZE_W:-}" ] || [ -z "${RLT_GAME_SIZE_H:-}" ]; then
        echo "RL-CONFIG native: RLT_GAME_SIZE_W/H not provided — profile patch skipped" | tee -a "$LOGFILE"
    fi
fi

# ===================================================================
# Build RuneLite classpath at runtime
# ===================================================================
# Scan repository2/ live — the stored direct-classpath.txt is stale (points
# at RL 1.12.20, rlawt-1.7; current disk has RL 1.12.24 + rlawt-1.8). See
# spec U2. Skip stock rlawt-*.jar so our Bionic variant resolves instead.
CLASSPATH_ENTRIES=""
SKIPPED_RLAWT=""
for jar in "$RL_REPO_DIR"/*.jar; do
    [ -f "$jar" ] || continue
    case "${jar##*/}" in
        rlawt-*.jar)
            SKIPPED_RLAWT="$jar"
            continue
            ;;
    esac
    if [ -z "$CLASSPATH_ENTRIES" ]; then
        CLASSPATH_ENTRIES="$jar"
    else
        CLASSPATH_ENTRIES="${CLASSPATH_ENTRIES}:${jar}"
    fi
done
if [ -z "$CLASSPATH_ENTRIES" ]; then
    echo "ERROR: repository2/ produced an empty classpath" | tee -a "$LOGFILE"
    exit 1
fi
# Prepend our Bionic rlawt jar so it shadows anything else.
FULL_CLASSPATH="${RLAWT_BIONIC_JAR}:${CLASSPATH_ENTRIES}"

echo "Classpath assembled:" | tee -a "$LOGFILE"
echo "  bionic-rlawt-jar=$RLAWT_BIONIC_JAR" | tee -a "$LOGFILE"
echo "  skipped-stock-rlawt=${SKIPPED_RLAWT:-<none>}" | tee -a "$LOGFILE"
echo "  repo-jar-count=$(ls "$RL_REPO_DIR"/*.jar 2>/dev/null | wc -l)" | tee -a "$LOGFILE"
echo "  classpath=$FULL_CLASSPATH" | tee -a "$LOGFILE"

# ===================================================================
# Invoke JVM directly — no proot
# ===================================================================
# LD_LIBRARY_PATH / JAVA_HOME already exported before the DIAG block above.
echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH" | tee -a "$LOGFILE"
echo "JAVA_HOME=$JAVA_HOME" | tee -a "$LOGFILE"

MAIN_CLASS="net.runelite.client.RuneLite"
# `--scale` is a flag on the RuneLite *launcher* (`java -jar RuneLite.jar`), NOT on the
# RuneLite client main class — joptsimple rejects it with UnrecognizedOptionException.
# Since we invoke the client main class directly (we're not going through the launcher),
# we use the equivalent AWT system property instead. -Dsun.java2d.uiScale controls AWT
# HiDPI scaling for the Frame + fonts without needing the launcher wrapper.
CLIENT_ARGS="--insecure-write-credentials --debug"

# UI scale is computed by LaunchCoordinator from context.resources.displayMetrics (the
# Android WindowManager Binder service isn't callable from run-as com.termux, so the
# Kotlin side is the only place with reliable access to the real display size). Falls
# back to 1.0 here if the env var is somehow missing (direct on-device shell invocation
# for testing) — never hardcode a specific scale.
UI_SCALE="${RLT_UI_SCALE:-1}"
if [ -n "${RLT_UI_SCALE:-}" ]; then
    echo "UI scale: $UI_SCALE (source=Kotlin-computed)" | tee -a "$LOGFILE"
else
    echo "UI scale: $UI_SCALE (source=fallback, set RLT_UI_SCALE to override)" | tee -a "$LOGFILE"
fi
# -Xss2m: Bionic default thread stack is ~1MB, JVM threads want more.
# -Xmx2g: matches device-memory budget used in proot path for small bench.
# -Duser.home points at the proot rootfs .runelite dir so native JVM sees
#   the same profiles2/, settings.properties, and credentials.properties.
# -Xlog:class+load=info: classload log proves main class was loaded, even
#   if RL's own loggers never initialize.
JVM_ARGS=(
    -Xss2m
    -Xmx2g
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=50
    "-Duser.home=$ROOTFS_PATH/root"
    # Java2D HiDPI scale — equivalent of the proot launcher's RuneLite-launcher `--scale`
    # flag but applied at the JVM level since we invoke the client main class directly.
    # Value comes from detect_ui_scale() above which reads the real display size; never
    # hardcode a specific scale here.
    "-Dsun.java2d.uiScale=$UI_SCALE"
    "-Xlog:class+load=info:file=$CLASSLOAD_LOG:time,uptime,level,tags:filecount=2,filesize=5M"
)
# Forward Jagex credentials from the env-file (if any) as system properties —
# RuneLite's auth layer reads these when sessionId / characterId are present,
# matching the proot path's env-based forwarding.
[ -n "${JX_SESSION_ID:-}" ]   && JVM_ARGS+=("-DJX_SESSION_ID=$JX_SESSION_ID")
[ -n "${JX_CHARACTER_ID:-}" ] && JVM_ARGS+=("-DJX_CHARACTER_ID=$JX_CHARACTER_ID")
[ -n "${JX_DISPLAY_NAME:-}" ] && JVM_ARGS+=("-DJX_DISPLAY_NAME=$JX_DISPLAY_NAME")

echo "=== Invoking JVM ===" | tee -a "$LOGFILE"
echo "  cmd: $JAVA_BIN ${JVM_ARGS[*]} -cp <classpath> $MAIN_CLASS $CLIENT_ARGS" | tee -a "$LOGFILE"
: > "$CLASSLOAD_LOG"
"$JAVA_BIN" "${JVM_ARGS[@]}" -cp "$FULL_CLASSPATH" "$MAIN_CLASS" $CLIENT_ARGS >> "$LOGFILE" 2>&1 &
JAVA_PID=$!
echo "$JAVA_PID" > "$HOME/.rlt-native.pid"
rlt_log_cpuset "java-started" "$JAVA_PID"
echo "JVM PID=$JAVA_PID (log $LOGFILE, classload $CLASSLOAD_LOG)" | tee -a "$LOGFILE"

# Pin JVM to big/prime cores (4-7). Same policy as proot path for A/B parity.
if taskset -p 0xF0 "$JAVA_PID" 2>/dev/null; then
    echo "AFFINITY: JVM (PID=$JAVA_PID) pinned to big/prime cores" | tee -a "$LOGFILE"
else
    echo "AFFINITY: taskset failed for JVM (PID=$JAVA_PID)" | tee -a "$LOGFILE"
fi

wait "$JAVA_PID"
JAVA_EXIT=$?
echo "JVM exited with code $JAVA_EXIT" | tee -a "$LOGFILE"
exit "$JAVA_EXIT"

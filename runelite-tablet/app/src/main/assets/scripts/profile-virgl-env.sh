#!/data/data/com.termux/files/usr/bin/bash
# VirGL environment profiler — captures pipeline configuration for diagnostics
# Output: VIRGL_ENV: prefixed key=value lines

echo "VIRGL_ENV: timestamp=$(date +%s)"

# Mesa/VirGL/Gallium env vars
for var in GALLIUM_DRIVER MESA_GL_VERSION_OVERRIDE MESA_NO_ERROR MESA_EXTENSION_OVERRIDE \
           VIRGL_DEBUG VTEST_SOCKET_NAME VIRGL_RENDERER_THREAD VIRGL_RENDERER_ASYNC \
           MESA_GLSL_CACHE_DISABLE MESA_SHADER_CACHE_MAX_SIZE LP_NUM_THREADS \
           GALLIUM_HUD LIBGL_ALWAYS_SOFTWARE; do
    val=$(eval echo "\$$var" 2>/dev/null)
    [ -n "$val" ] && echo "VIRGL_ENV: env_${var}=$val"
done

# VirGL server process state
VIRGL_PID=$(pidof virgl_test_server_android 2>/dev/null || pidof virgl_test_server 2>/dev/null)
if [ -n "$VIRGL_PID" ]; then
    echo "VIRGL_ENV: virgl_pid=$VIRGL_PID"
    VIRGL_CPU=$(awk '{print $39}' "/proc/$VIRGL_PID/stat" 2>/dev/null)
    VIRGL_THREADS=$(ls "/proc/$VIRGL_PID/task/" 2>/dev/null | wc -l)
    VIRGL_RSS=$(awk '/VmRSS/{print $2}' "/proc/$VIRGL_PID/status" 2>/dev/null)
    echo "VIRGL_ENV: virgl_cpu=$VIRGL_CPU threads=$VIRGL_THREADS rss_kb=$VIRGL_RSS"
else
    echo "VIRGL_ENV: virgl_pid=none"
fi

# ANGLE version (via env vars or library)
for avar in ANGLE_DEFAULT_PLATFORM ANGLE_PLATFORM_ANGLE_TYPE; do
    aval=$(eval echo "\$$avar" 2>/dev/null)
    [ -n "$aval" ] && echo "VIRGL_ENV: angle_${avar}=$aval"
done
ANGLE_LIB=$(find "$PREFIX/lib" -name "libEGL_angle.so" 2>/dev/null | head -1)
if [ -n "$ANGLE_LIB" ]; then
    ANGLE_SIZE=$(stat -c %s "$ANGLE_LIB" 2>/dev/null || echo "unknown")
    echo "VIRGL_ENV: angle_lib=$ANGLE_LIB size=$ANGLE_SIZE"
else
    echo "VIRGL_ENV: angle_lib=not_found"
fi

# Socket health
for sock in /tmp/.virgl_test /tmp/.virgl_test_socket; do
    if [ -e "$sock" ]; then
        echo "VIRGL_ENV: socket=$sock exists=true type=$(stat -c %F "$sock" 2>/dev/null || echo unknown)"
        break
    fi
done

# GPU clock state (if accessible)
for devfreq in /sys/class/devfreq/*/cur_freq; do
    [ -f "$devfreq" ] || continue
    freq=$(cat "$devfreq" 2>/dev/null)
    name=$(basename "$(dirname "$devfreq")")
    echo "VIRGL_ENV: gpu_clock_${name}=$freq"
done

echo "VIRGL_ENV: done"

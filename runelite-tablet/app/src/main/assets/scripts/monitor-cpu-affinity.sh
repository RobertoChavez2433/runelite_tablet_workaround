#!/data/data/com.termux/files/usr/bin/bash
# CPU core affinity monitor — reads /proc/PID/stat field 39 every 2s
# Classifies cores by max freq: little (<= 2100MHz), big (<= 2900MHz), prime (> 2900MHz)
# Usage: monitor-cpu-affinity.sh PID1 PID2 ...

POLL_INTERVAL=2
BIG_CORE_MASK="0xF0"   # CPUs 4-7

classify_core() {
    local cpu=$1
    local max_freq
    max_freq=$(cat "/sys/devices/system/cpu/cpu${cpu}/cpufreq/cpuinfo_max_freq" 2>/dev/null || echo "0")
    if [ "$max_freq" -le 2100000 ] 2>/dev/null; then
        echo "little"
    elif [ "$max_freq" -le 2900000 ] 2>/dev/null; then
        echo "big"
    else
        echo "prime"
    fi
}

get_cpu_field() {
    # Field 39 (0-indexed from field 1) of /proc/PID/stat is the CPU number
    local pid=$1
    local stat
    stat=$(cat "/proc/${pid}/stat" 2>/dev/null) || return 1
    echo "$stat" | awk '{print $39}'
}

get_freq() {
    local cpu=$1
    cat "/sys/devices/system/cpu/cpu${cpu}/cpufreq/scaling_cur_freq" 2>/dev/null || echo "0"
}

repin_if_needed() {
    local pid=$1 cpu=$2 core_type=$3
    if [ "$core_type" = "little" ]; then
        local old_cpu=$cpu
        taskset -p "$BIG_CORE_MASK" "$pid" 2>/dev/null
        local new_cpu
        new_cpu=$(get_cpu_field "$pid") || return
        local new_type
        new_type=$(classify_core "$new_cpu")
        echo "AFFINITY_MIGRATION: pid=$pid from=${old_cpu}(${core_type}) to=${new_cpu}(${new_type}) re-pinned=true"
    fi
}

while true; do
    for pid in "$@"; do
        [ -d "/proc/$pid" ] || continue
        cpu=$(get_cpu_field "$pid") || continue
        core_type=$(classify_core "$cpu")
        freq=$(get_freq "$cpu")
        name=$(cat "/proc/$pid/comm" 2>/dev/null || echo "unknown")
        echo "AFFINITY: pid=$pid name=$name cpu=$cpu type=$core_type freq=$freq"
        repin_if_needed "$pid" "$cpu" "$core_type"
    done
    sleep "$POLL_INTERVAL"
done

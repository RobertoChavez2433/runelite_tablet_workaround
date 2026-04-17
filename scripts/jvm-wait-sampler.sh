#!/system/bin/sh
# JVM / VirGL / proot off-CPU wait sampler — Slice 5 Path B diagnostic.
#
# Runs ON DEVICE under `run-as com.termux`. Polls /proc/<pid>/task/<tid>/{comm,status,schedstat,stat}
# for every thread of the given PIDs. Emits:
#   * START and END snapshots of per-thread voluntary/nonvoluntary ctxt-switches + schedstat
#   * A sample line per tick per thread with state letter + wchan address (field 35 of stat)
#
# Android non-root limits:
#   - /proc/<pid>/wchan returns "0" (symbol resolution needs CAP_SYS_ADMIN). We read the raw
#     wchan ADDRESS from stat field 35 instead — clustering on the address still tells us
#     whether waits are concentrated at one kernel site or scattered.
#   - /proc/<pid>/syscall is blocked too. State letter (R/S/D/t/T) is our ptrace-stop signal.
#
# Usage (from host):
#   adb push scripts/jvm-wait-sampler.sh /data/local/tmp/
#   adb shell run-as com.termux cp /data/local/tmp/jvm-wait-sampler.sh /data/data/com.termux/files/home/
#   adb shell run-as com.termux sh /data/data/com.termux/files/home/jvm-wait-sampler.sh \
#       "17652 17870 17239" 60 /data/data/com.termux/files/home/jvm-wait.log
#   adb shell run-as com.termux cat /data/data/com.termux/files/home/jvm-wait.log > slice5-jvm-wait-60s.log

set -u

PIDS="${1:-}"
DURATION="${2:-60}"
OUT="${3:-/data/data/com.termux/files/home/jvm-wait.log}"
INTERVAL_SEC="${4:-0.1}"

if [ -z "$PIDS" ]; then
    echo "usage: $0 \"<pid1> [pid2] [pid3]\" [duration_sec] [out_file] [interval_sec]" >&2
    exit 2
fi

: > "$OUT"
echo "=== SAMPLER START ts_ns=$(date +%s%N) pids=\"$PIDS\" duration=${DURATION}s interval=${INTERVAL_SEC}s ===" >> "$OUT"

# --- snapshot helper ------------------------------------------------------
# $1=label ("START"|"END"), $2=pid
snapshot() {
    LABEL="$1"; PID="$2"
    [ -d "/proc/$PID" ] || return 0
    for taskdir in /proc/$PID/task/*; do
        [ -d "$taskdir" ] || continue
        TID=${taskdir##*/}
        COMM=$(cat "$taskdir/comm" 2>/dev/null)
        SCHED=$(cat "$taskdir/schedstat" 2>/dev/null)
        # Extract three counters we care about from /status
        VOL=$(grep '^voluntary_ctxt_switches' "$taskdir/status" 2>/dev/null | awk '{print $2}')
        NONVOL=$(grep '^nonvoluntary_ctxt_switches' "$taskdir/status" 2>/dev/null | awk '{print $2}')
        STATE=$(grep '^State:' "$taskdir/status" 2>/dev/null | awk '{print $2}')
        CPUS=$(grep '^Cpus_allowed_list' "$taskdir/status" 2>/dev/null | awk '{print $2}')
        echo "${LABEL}|pid=$PID|tid=$TID|comm=${COMM}|state=${STATE}|cpus=${CPUS}|vol=${VOL:-0}|nonvol=${NONVOL:-0}|sched=${SCHED}" >> "$OUT"
    done
}

echo "=== START SNAPSHOT ts_ns=$(date +%s%N) ===" >> "$OUT"
for PID in $PIDS; do snapshot START "$PID"; done

# --- sample loop ----------------------------------------------------------
# Use second-granularity bounds to avoid 19-digit int overflow in /system/bin/sh.
START_SEC=$(date +%s)
END_SEC=$(( START_SEC + DURATION ))

echo "=== SAMPLES (ts_ns|pid|tid|state|wchan_addr) ===" >> "$OUT"
SAMPLE_COUNT=0
while :; do
    NOW_SEC=$(date +%s)
    [ "$NOW_SEC" -ge "$END_SEC" ] && break
    NOW_NS=$(date +%s%N)
    for PID in $PIDS; do
        [ -d "/proc/$PID" ] || continue
        for taskdir in /proc/$PID/task/*; do
            [ -d "$taskdir" ] || continue
            TID=${taskdir##*/}
            # stat line: "PID (COMM) STATE PPID PGRP ... wchan=field35". COMM can contain spaces,
            # so split after the last ')' and index from there.
            STAT=$(cat "$taskdir/stat" 2>/dev/null) || continue
            REST=${STAT##*)}
            # REST starts with " STATE PPID PGRP ... " — fields 3.. mapped to positions 1..
            # wchan is stat field 35 -> REST position 33.
            STATE=$(echo "$REST" | awk '{print $1}')
            WCHAN=$(echo "$REST" | awk '{print $33}')
            echo "$NOW_NS|$PID|$TID|$STATE|${WCHAN:-0}" >> "$OUT"
        done
    done
    SAMPLE_COUNT=$(( SAMPLE_COUNT + 1 ))
    sleep "$INTERVAL_SEC"
done

echo "=== END SNAPSHOT ts_ns=$(date +%s%N) sample_ticks=$SAMPLE_COUNT ===" >> "$OUT"
for PID in $PIDS; do snapshot END "$PID"; done

echo "=== SAMPLER DONE ts_ns=$(date +%s%N) ===" >> "$OUT"
echo "DONE: $OUT (ticks=$SAMPLE_COUNT)"

#!/usr/bin/env python3
"""Parse jvm-wait-sampler.sh output → per-thread aggregates + interpretation."""
from __future__ import annotations

import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

LOG = Path(sys.argv[1])
raw = LOG.read_text().splitlines()

# START|pid=17652|tid=17652|comm=java|state=S|cpus=0-7|vol=112|nonvol=0|sched=6213084 1384074 112
SNAP_RE = re.compile(
    r"^(START|END)\|pid=(\d+)\|tid=(\d+)\|comm=(.*?)\|state=(\S)\|cpus=\S+\|vol=(\d+)\|nonvol=(\d+)\|sched=(\d+) (\d+) (\d+)$"
)
# 1776430547665337877|17652|17652|S|1
SAMP_RE = re.compile(r"^(\d+)\|(\d+)\|(\d+)\|(\S)\|(\S+)$")

starts: dict[int, dict] = {}
ends: dict[int, dict] = {}
state_hist: dict[int, Counter] = defaultdict(Counter)
sample_count: dict[int, int] = defaultdict(int)
comm_by_tid: dict[int, str] = {}
pid_by_tid: dict[int, int] = {}

ts_start_ns = None
ts_end_ns = None

for line in raw:
    if line.startswith("=== SAMPLER START"):
        m = re.search(r"ts_ns=(\d+)", line)
        if m: ts_start_ns = int(m.group(1))
    elif line.startswith("=== END SNAPSHOT"):
        m = re.search(r"ts_ns=(\d+)", line)
        if m: ts_end_ns = int(m.group(1))
    m = SNAP_RE.match(line)
    if m:
        kind, pid, tid, comm, state, vol, nonvol, run_ns, wait_ns, nperiods = m.groups()
        tid = int(tid); pid = int(pid)
        comm_by_tid[tid] = comm
        pid_by_tid[tid] = pid
        entry = {
            "state": state,
            "vol": int(vol),
            "nonvol": int(nonvol),
            "run_ns": int(run_ns),
            "wait_ns": int(wait_ns),
            "nperiods": int(nperiods),
        }
        (starts if kind == "START" else ends)[tid] = entry
        continue
    m = SAMP_RE.match(line)
    if m:
        ts, pid, tid, state, _wchan = m.groups()
        tid = int(tid)
        state_hist[tid][state] += 1
        sample_count[tid] += 1

duration_s = (ts_end_ns - ts_start_ns) / 1e9 if ts_start_ns and ts_end_ns else 60.0

# Build per-thread deltas
rows = []
for tid, s in starts.items():
    e = ends.get(tid)
    if e is None:
        continue
    d_vol = e["vol"] - s["vol"]
    d_nonvol = e["nonvol"] - s["nonvol"]
    d_run = e["run_ns"] - s["run_ns"]
    d_wait = e["wait_ns"] - s["wait_ns"]
    d_np = e["nperiods"] - s["nperiods"]
    hist = state_hist[tid]
    total_samples = sample_count[tid] or 1
    on_cpu_pct = hist.get("R", 0) * 100.0 / total_samples
    sleep_pct = hist.get("S", 0) * 100.0 / total_samples
    dsleep_pct = hist.get("D", 0) * 100.0 / total_samples
    tracestop_pct = (hist.get("t", 0) + hist.get("T", 0)) * 100.0 / total_samples
    # delta_run_ns = CPU time this thread received during the window
    cpu_util_pct = d_run * 100.0 / (duration_s * 1e9) if duration_s > 0 else 0
    # delta_wait_ns = time this thread was runnable but not on CPU (contention)
    wait_pct = d_wait * 100.0 / (duration_s * 1e9) if duration_s > 0 else 0
    rows.append({
        "tid": tid,
        "pid": pid_by_tid[tid],
        "comm": comm_by_tid[tid],
        "d_vol": d_vol,
        "d_nonvol": d_nonvol,
        "vol_rate": d_vol / duration_s,
        "nonvol_rate": d_nonvol / duration_s,
        "cpu_util_pct": cpu_util_pct,
        "wait_pct": wait_pct,
        "on_cpu_pct": on_cpu_pct,
        "sleep_pct": sleep_pct,
        "dsleep_pct": dsleep_pct,
        "tracestop_pct": tracestop_pct,
        "hist": dict(hist),
        "samples": total_samples,
    })

rows.sort(key=lambda r: r["cpu_util_pct"], reverse=True)

print(f"=== Summary: duration={duration_s:.2f}s, threads={len(rows)} ===")
print()
print(f"{'tid':>6} {'comm':<20} {'cpu%':>6} {'wait%':>6} {'vol/s':>8} {'nonv/s':>8} {'R%':>5} {'S%':>5} {'D%':>4} {'t/T%':>5} {'samples':>7}")
print("-"*95)
for r in rows:
    if r["cpu_util_pct"] < 0.1 and r["d_vol"] < 5 and r["d_nonvol"] < 5:
        continue  # hide idle threads
    print(f"{r['tid']:>6} {r['comm'][:20]:<20} {r['cpu_util_pct']:>6.2f} {r['wait_pct']:>6.2f} "
          f"{r['vol_rate']:>8.1f} {r['nonvol_rate']:>8.1f} "
          f"{r['on_cpu_pct']:>5.1f} {r['sleep_pct']:>5.1f} {r['dsleep_pct']:>4.1f} "
          f"{r['tracestop_pct']:>5.1f} {r['samples']:>7}")

# --- aggregate roll-up by role -------------------------------------------
print()
print("=== Role roll-up (sum of CPU%) ===")
roles = {
    "RuneLite Client render thread": lambda r: r["comm"] == "Client",
    "JVM system (GC/JIT/AWT/VM/etc)": lambda r: r["pid"] == 17652 and r["comm"] != "Client",
    "virgl_test_server (all threads)": lambda r: r["pid"] == 17870,
    "proot tracer": lambda r: r["pid"] == 17239,
}
for label, pred in roles.items():
    sub = [r for r in rows if pred(r)]
    if not sub:
        continue
    cpu_sum = sum(r["cpu_util_pct"] for r in sub)
    vol_sum = sum(r["d_vol"] for r in sub) / duration_s
    nonvol_sum = sum(r["d_nonvol"] for r in sub) / duration_s
    d_total = sum(r["dsleep_pct"] * r["samples"] for r in sub) / max(1, sum(r["samples"] for r in sub))
    t_total = sum(r["tracestop_pct"] * r["samples"] for r in sub) / max(1, sum(r["samples"] for r in sub))
    print(f"  {label:<40} cpu={cpu_sum:>6.2f}% vol/s={vol_sum:>9.1f} nonvol/s={nonvol_sum:>8.1f} D%={d_total:>4.1f} t/T%={t_total:>4.1f}")

# --- verdict heuristics ---------------------------------------------------
print()
print("=== Heuristic verdict ===")
client = next((r for r in rows if r["comm"] == "Client"), None)
virgl_main = next((r for r in rows if r["pid"] == 17870 and r["comm"].startswith("virgl_test_serv")), None)
proot_rows = [r for r in rows if r["pid"] == 17239]

if client:
    print(f"  Client thread cpu={client['cpu_util_pct']:.1f}% vol_rate={client['vol_rate']:.0f}/s nonvol_rate={client['nonvol_rate']:.0f}/s")
    print(f"    wait_for_cpu%={client['wait_pct']:.2f}% (runnable but not on CPU)")
    print(f"    state histogram: {client['hist']}")
    if client["tracestop_pct"] > 5:
        print("    >> HIGH tracing-stop time — proot ptrace is slowing this thread.")
    elif client["dsleep_pct"] > 5:
        print("    >> HIGH D-state — true iowait (uncommon unless disk/swap).")
    elif client["on_cpu_pct"] < 5 and client["vol_rate"] > 200:
        print("    >> Very high voluntary ctxt-switch rate with low on-CPU samples: frequent short blocks.")
        print("       Classic signature of poll()/recvmsg() ping-pong on a socket fd (VirGL or X11).")
    elif client["wait_pct"] > 50:
        print("    >> High wait-for-CPU time — runnable but can't get on CPU. CPU contention.")
    else:
        print("    >> Mixed signals; inspect per-sample history manually.")

if virgl_main:
    print(f"  virgl main thread cpu={virgl_main['cpu_util_pct']:.1f}% vol_rate={virgl_main['vol_rate']:.0f}/s")
    if client and virgl_main["vol_rate"] > 0 and client["vol_rate"] > 0:
        ratio = client["vol_rate"] / virgl_main["vol_rate"]
        print(f"  Client/virgl vol_rate ratio = {ratio:.2f}  (~1.0 means they're ping-ponging 1:1)")

if proot_rows:
    p = proot_rows[0]
    print(f"  proot tracer cpu={p['cpu_util_pct']:.1f}% vol_rate={p['vol_rate']:.0f}/s nonvol_rate={p['nonvol_rate']:.0f}/s")
    print(f"    (proot's own syscall rate roughly = 2× number of JVM syscalls intercepted per sec)")

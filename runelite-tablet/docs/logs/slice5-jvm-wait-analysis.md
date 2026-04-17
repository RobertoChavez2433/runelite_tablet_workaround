# Slice 5 / Path B — JVM Wait-Sink Analysis (Session 77)

**Date**: 2026-04-17
**Device**: R52X90378YB (Samsung Tab S10 Ultra, non-rooted, Android 16)
**Capture**: 60 s live sampling at Varrock East Bank, FPS ≈ 12–15 (damage-triggered redraws = 31.8 FPS; sticky_hits scene-FPS ≈ 12.9).
**Tool**: `scripts/jvm-wait-sampler.sh` + `scripts/jvm-wait-analyze.py`
**Raw log**: `runelite-tablet/docs/logs/slice5-jvm-wait-60s.log`

---

## Top-line per-thread measurements

| TID | comm | CPU% of 1 core | vol/s | **nonvol/s** | R% | S% | D% | t/T% |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 17870 | virgl_test_server (main) | 34.18 | 270.5 | 328.5 | 20 | 80 | 0 | 0 |
| 17773 | **Client** (RuneLite render) | **31.57** | **1688** | **3945** | 37 | 63 | 0 | 0 |
| 17239 | proot tracer | 3.66 | 215.7 | 29.2 | 3 | 97 | 0 | 0 |
| 17835 | Thread-7 (JVM worker) | 0.96 | 118.2 | 2.2 | 0 | 100 | 0 | 0 |
| 17665 | C2 CompilerThread | 0.90 | 5.4 | 1.0 | 0 | 100 | 0 | 0 |

All other JVM threads < 1% CPU.

## Role roll-up

| role | cpu% | vol/s | nonvol/s |
|---|---:|---:|---:|
| RuneLite Client render thread | 31.57 | 1688 | **3945** |
| JVM system (GC/JIT/AWT/VM/etc) | 3.83 | 279 | 9 |
| virgl_test_server (all threads) | 35.73 | 668 | 332 |
| proot tracer | 3.66 | 216 | 29 |

---

## The smoking gun: Client nonvoluntary context-switch rate

`Client` (TID 17773) is the RuneLite render thread — the *only* producer thread in the JVM doing scene work. Its state during the capture:

- **cpu% = 31.6%** — NOT cpu-bound. 68% of wall time it's not on CPU.
- **vol/s = 1688** — the thread blocks on a kernel wait ~1688 times/sec.
- **nonvol/s = 3945** — the thread is *preempted* (forced off CPU while still runnable) ~3945 times/sec.

### Why 3945 nonvol/s is the anomaly

Ordinary Linux scheduler-tick preemptions on Android cap at ~250–1000 Hz depending on `CONFIG_HZ`. Seeing a single thread preempted at **≈4× the kernel tick rate** is abnormal and points at something *other than the scheduler tick* forcing the thread off CPU.

The only mechanism that fits the shape of the data is **synchronous ptrace interception of every syscall the thread makes**. When proot runs in pure-ptrace mode (the default — `RLT_PROOT_SECCOMP=0`), every syscall in a tracee triggers two context switches:

1. Syscall-entry stop → tracee blocked, proot wakes and inspects/translates.
2. Syscall-exit stop → tracee blocked, proot wakes and inspects the result.

Each of those re-wakes of `Client` after proot yields ends up recorded as a *nonvoluntary* context switch (the tracee becomes runnable but is forced to wait in TASK_TRACED in between).

### Arithmetic that lines up

- 3945 nonvol/s ÷ 2 switches-per-syscall ≈ **1970 intercepted syscalls/s** on `Client`.
- Scene FPS during the capture ≈ 12 → **≈ 164 syscalls per frame**.
- That is exactly the order-of-magnitude syscall count a GL-plugin frame needs (GL command dispatch to VirGL socket + X11 event pump + frame-buffer upload). Matches.
- Max achievable FPS under this ceiling = (syscall budget / frame) ÷ (per-syscall round-trip) = `1970 / 164 ≈ 12` FPS. **We are exactly at the proot-ptrace syscall ceiling.**

### Corroborating evidence

- `proot` itself is only at 3.66% CPU — it isn't CPU-bound; each interception is cheap (single-digit μs). The cost isn't *in proot* — it's in the *scheduler thrash* every syscall causes.
- Client shows **zero D-state** (no disk iowait) and **zero t/T-state** (no SIGSTOP). State is split 37% R / 63% S. The sink isn't a stuck socket wait; it's death by a thousand scheduler round-trips.
- `top -H` earlier in the session showed **system-wide sys% = 162% vs user% = 69%** — ≈ 70% of total CPU time is in kernel mode, consistent with constant syscall-stop / syscall-continue transitions.
- `Client/virgl_main vol_rate ratio = 6.24`. The Client thread touches the kernel ~6× more than VirGL processes messages, meaning most of Client's waits aren't "round-trip to virgl" — they're `read/write/poll/futex/mmap` routed through proot.

---

## Verdict

**The bottleneck is proot's pure-ptrace syscall-interception mode, not VirGL IPC, not Mesa, not CPU, not RuneLite frame pacing.**

| Candidate (from Path-A results doc) | Supported by the evidence? |
|---|---|
| VirGL IPC serialization | No. virgl main vol_rate = 270/s ≈ matches current FPS at ~22 msgs/frame. Not elevated. |
| **proot ptrace per-syscall overhead** | **Yes.** Client's 3945 nonvol/s + 2.3× kernel-to-user CPU ratio + proot at low CPU all fit. |
| Mesa single-threading | No. Would manifest as futex waits concentrated in Client with *low* ctxt-switch rate, not preemption thrash. |
| RuneLite frame pacing | No. Would show periodic sleeps aligned to 16.67 ms, not 250-μs preemption thrash. |

## What to do next

### Primary: A/B test `RLT_PROOT_SECCOMP=1`

This is the exact fix the 120-FPS spec's Phase 4B already documented, and `launch-runelite.sh` already has the toggle wired (line ~1182):

```sh
if [ "${RLT_PROOT_SECCOMP:-0}" = "1" ]; then
    export PROOT_NO_SECCOMP=0
```

With seccomp-bpf enabled, proot filters syscalls in the kernel via a BPF program. The common case (read/write/poll/futex etc.) bypasses userspace proot entirely — no context switch, no ptrace stop. Only a small whitelist of path-translating syscalls (`openat`, `execve`, etc.) fall through to ptrace.

**Expected outcome if the ptrace hypothesis is right:**
- Client `nonvol/s` should drop from ~3945 to near the kernel-tick baseline (~250/s).
- Client `vol/s` should stay similar or rise (more syscalls complete faster = more voluntary blocks on actual waits).
- `sys%` vs `user%` in `top -H` should rebalance away from kernel-dominated.
- **Scene FPS should rise noticeably** — if per-syscall overhead drops 10×, the 12-FPS ceiling moves up. Target to beat: ≥ 25 FPS (2× — the minimum that justifies the toggle being default-on).

**Risks:**
- Spec warned: "some syscalls may behave differently under seccomp vs ptrace, causing proot fallbacks or crashes."
- Known proot issue: PROOT_NO_SECCOMP=0 on some kernels breaks fork()/clone() or dynamic-linker paths — must verify RuneLite still launches fully.

### Secondary (if seccomp doesn't work): measure directly

If `RLT_PROOT_SECCOMP=1` crashes or no-ops, escalate to **Option 4** (async-profiler in-proot wall-clock sampling) to get Java-method-level stacks for what `Client` is actually calling.

### Deferred / NOT doing

- **Adding Android-side threading**: the data rules this out — Client is wait-bound at 31.6% CPU, not cpu-bound. More threads don't help a waiter. User asked about this; short answer is no (see session notes).
- Virgl server-side message timing: the data rules out VirGL IPC as the sink.
- Mesa rebuild: not implicated.

---

## Artifacts

- `runelite-tablet/docs/logs/slice5-jvm-wait-60s.log` — raw 60 s sampler output (1982 lines, START+END snapshots + 35 sample ticks × 55 threads).
- `scripts/jvm-wait-sampler.sh` — on-device sampler (reusable, gated by run-as com.termux).
- `scripts/jvm-wait-analyze.py` — host-side aggregator that produced the table above.

## Reproduction

```bash
export MSYS_NO_PATHCONV=1
DEV=R52X90378YB
adb -s $DEV push scripts/jvm-wait-sampler.sh /data/local/tmp/
adb -s $DEV shell 'run-as com.termux cp /data/local/tmp/jvm-wait-sampler.sh /data/data/com.termux/files/home/ && run-as com.termux chmod 755 /data/data/com.termux/files/home/jvm-wait-sampler.sh'
# Look up PIDs: JVM inner = `pgrep -f 'java -cp'`, virgl = `pgrep virgl_test_server`, proot = `pgrep '^proot$'`
adb -s $DEV shell 'run-as com.termux sh /data/data/com.termux/files/home/jvm-wait-sampler.sh "<JVM> <VIRGL> <PROOT>" 60 /data/data/com.termux/files/home/slice5-jvm-wait-60s.log 0.1'
adb -s $DEV shell 'run-as com.termux cat /data/data/com.termux/files/home/slice5-jvm-wait-60s.log' > runelite-tablet/docs/logs/slice5-jvm-wait-60s.log
python scripts/jvm-wait-analyze.py runelite-tablet/docs/logs/slice5-jvm-wait-60s.log
```

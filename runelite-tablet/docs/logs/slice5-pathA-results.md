# Path A (Termux bind-hoist) — Session 76 Results

**Date**: 2026-04-17 | **Session**: 76 | **Device**: R52X90378YB
**Parent plan**: `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`
**Status**: **Landed, measured, refuted. Do not revisit this approach.**

## What Path A was

`TermuxProcessPin.kt` holds a `bindService(BIND_IMPORTANT | BIND_INCLUDE_CAPABILITIES | BIND_AUTO_CREATE)` against `com.termux.app.RunCommandService` for the lifetime of the RLT foreground service. Hypothesis: Android scheduler promotes Termux's process to `PROCESS_STATE_BOUND_TOP` → `/top-app` cpuset → proot/JVM/virgl all inherit CPUs 0-7 for free.

S74 had proved Termux's default `/background` cpuset (CPUs 0-3, little cluster only) was clamping the producer pipeline. The hypothesis was: break the cpuset clamp, and FPS rises from 13 toward the 120 Hz the compositor has headroom for.

## What landed

- `runelite-tablet/app/src/main/java/com/runelitetablet/termux/TermuxProcessPin.kt` (new, 103 LoC)
- `RuneLiteSessionService.kt`: pin after `startForeground()`, unpin on Stopped/Error/onDestroy
- `launch-runelite.sh`: `rlt_log_cpuset` helper at script-entry, virgl-ready, java-started; **important bash fix** — compound `local a="$1" b="$2"` is broken under `set -u` (see `feedback_bash_set_u_local_multiassign.md`), must be one `local` per line

## Key verified facts

### The bind itself works
```
TermuxProcessPin: bindService success flags=0x1041
TermuxProcessPin: onServiceConnected name=... binder=true
```
Kotlin + Android plumbing is correct. Samsung AMS confirms the bound Termux process enters `PROCESS_STATE_BOUND_TOP`.

### BOUND_TOP → `/top-app` is timing-dependent
Two device runs, same APK, different outcomes:
- **Attempt 2** (RLT activity foreground when bind fired): `CPUSET launch-script-entry pid=16618 cpuset=/top-app` ✓
- **Attempt 1** (RLT activity not at top when bind fired): `cpuset=/moderate` — same 0-3 CPU mask as `/background`

For Path A to succeed, the RLT `MainActivity` must be top-activity at the moment `handleStartSession()` calls `termuxPin.pin()`. If the user backs out or switches away, the bind lands in `/moderate` instead. **This is inherent and fragile** — shipping Path A in production requires tolerating this ambiguity.

### On the successful attempt, scheduler state was exactly as hypothesized
| process | cpuset | Cpus_allowed_list | freq |
|---|---|---|---|
| launch-runelite.sh (pid 16618) | /top-app | 0-7 | — |
| virgl_test_server (pid 16898) | /top-app | **4-7** (via taskset 0xF0) | 2.9 GHz big |
| JVM main (pid 17584) | /top-app | **4-7** (via taskset 0xF0) | 2.9 GHz big |
| JVM sub-threads | /top-app | **0-7** (inherit cpuset) | — |

The JVM can in principle use any of the 8 cores. virgl is pinned to big/prime.

### Cpuset topology on R52X90378YB (verified by read)
| cpuset | cpus |
|---|---|
| `/background` | 0-3 (little) |
| `/moderate` | **0-3** (same as background) |
| `/foreground` | 0-6 |
| `/top-app` | 0-7 (system-readable only, but processes in it show Cpus_allowed=0xff) |

**Important**: BOUND_TOP does NOT deterministically map to `/top-app`. Samsung may place the promoted process in `/moderate` (functionally identical to `/background`) depending on foreground-activity state at bind time.

### In-game FPS at Varrock East Bank — 60s capture
| metric | S74 baseline (cpuset=/background) | S76 Path A (cpuset=/top-app, cpus 4-7) |
|---|---|---|
| User-visible FpsPlugin overlay | 13 | **12-15** |
| damage-triggered redraws (5s avg) | 47 | 25-32 |
| sticky_hits/5s | ~4800 | ~5000-5300 |
| scene FPS from sticky_hits÷84 | ~11.4 | ~12.4 |
| compositor choreographer | 120 | 120 |
| **CPU utilization across all 8 cores** | — | **~27% of 800%** |

**Delta in scene FPS: +1 FPS or noise. Zero meaningful gain.**

### The smoking gun: CPU is not the bottleneck
Per-core delta over 2s under live gameplay at Varrock East Bank:
- cpu0-3 (little): 25-41% each
- cpu4-7 (big/prime): 16-27% each
- Total: ~27% system utilization

Even with the JVM and virgl free to use big/prime cores, **no core is pegged**. The system has ~73% idle. Scene FPS is not rising because CPU was never the limit.

## Why S74's "cpuset clamp is the bottleneck" theory was wrong

S74 observed 13 FPS in `/background` (CPUs 0-3). S76 observed ~12-15 FPS in `/top-app` (CPUs 0-7) with virgl+JVM main on big cores. Same FPS, different cpuset. Therefore cpuset was not the FPS-determining factor.

The theory was seductive because:
1. `/background`'s 0-3 cap was real and writeable confirmed.
2. `taskset 0xF0` silently failed under `/background` (mask intersection → effective 0x0F).
3. "Little cores only" is a plausible cause for 13 FPS.

It was wrong because:
1. We never measured whether *moving* to big cores would lift FPS before the Path A refactor.
2. The observation that the system stays 70%+ idle even on big cores refutes CPU-bound.

## Where the bottleneck actually is (per S76 evidence)

System is not CPU-bound. The producer pipeline sits idle between frame emissions. Candidate serialization points (ordered by likelihood):

1. **VirGL IPC serialization** — every GL call guest↔host crosses the virgl_test_server socket as a vtest message. Single socket = single-threaded throughput. 19% kernel time in the 800% CPU breakdown is consistent with this (socket syscalls + ptrace).
2. **proot ptrace syscall translation** — every syscall in the JVM subtree is intercepted. Even with big cores, the ptrace round-trip is serialized per-syscall. `RLT_PROOT_SECCOMP=1` is an untested toggle that skips ptrace for "safe" syscalls. Part 1 Phase 3 of the 120 FPS spec.
3. **Mesa GL driver single-threaded** — Mesa's virgl guest driver is not multi-threaded in the hot path. Pinning to one big core gives no benefit if Mesa serializes on its own mutexes.
4. **RuneLite GPU plugin frame pacing** — the GL plugin's `glBlitFramebuffer` → SwapBuffers sequence may itself throttle at a hard ceiling independent of CPU/cpuset.

## What we must NOT do again

1. **Do not propose more cpuset/scheduler hoisting approaches.** S75→S76 proved the approach works on the Android side AND that the scheduler landing doesn't move scene FPS. Further variants (BIND_IMPORTANT_BOOSTED, sched_setaffinity via JNI, manual `/dev/cpuset/*/tasks` write) would all land in the same scheduler state we already verified and would not change FPS.
2. **Do not write off `/moderate` vs `/top-app` as "just a timing thing to fix".** Even if we guarantee `/top-app` placement deterministically (requires tighter lifecycle coupling), we still land at 12-15 FPS.
3. **Do not assume compositor/presentation optimization will help.** S74 proved compositor has 2.5× idle headroom (120 Hz capacity vs 47 Hz demand). S76 confirms the demand is now even lower (25-32 damage FPS). Lifting presentation FPS ceiling does nothing if producer FPS is 12.

## Path B candidates (where to go next)

Listed in the order the spec's Part 1 phases suggest, with updated prior cost estimates:

### B1. `RLT_PROOT_SECCOMP=1` toggle
- **Cost**: 1 env flag, probably <10 LoC in launch-runelite.sh
- **Expected upside**: spec claims kernel_ratio drops from 0.54 to 0.20 — but that was theory. Actual win unknown.
- **Risk**: some syscalls may behave differently under seccomp vs ptrace, causing proot fallbacks or crashes.
- **Worth trying first** — cheap.

### B2. Measure where the JVM actually waits
- Use `perf` / `schedstat` / `strace -c` on the JVM to find the top syscall-wait sink. If it's `poll`/`epoll_wait` on the X11 socket or VirGL IPC fd, that's proof.
- **Cost**: diagnostic only, no code change.
- **Must happen before committing to any larger refactor.**

### B3. Bundle virgl + proot launch into the RLT foreground service
- **Previously considered Path B** but this was framed as a cpuset fix. Now the justification is different: if it consolidates the pipeline into a single process tree under direct RLT control, we might be able to skip proot's ptrace for the virgl bridge. But this does NOT solve VirGL IPC serialization — it's the same socket protocol.
- **Cost**: ~200+ LoC Kotlin + native. Significant.
- **Expected upside**: unclear post-S76. Don't start without B2 evidence.

### B4. AHB zero-copy / custom GL command proxy (spec Phase 5/6)
- Bypass VirGL entirely and have rlawt submit GL commands via an Android-native GL proxy with AHB-backed buffers.
- **Cost**: months. Multiple native components.
- **Last resort**. Only if B1 + B2 + B3 collectively fail.

### B5. Accept the ceiling
- Document 13 FPS as the architectural limit of proot-VirGL-Mesa on Mali-G720 without deep Mesa changes.
- Keep the project as a "runs RuneLite, unplayable FPS, useful for reference" demo.

## Artifacts

- `runelite-tablet/docs/logs/slice5-pathA-bind-attempt2.log` — first attempt, landed in /moderate
- `runelite-tablet/docs/logs/slice5-pathA-bind-attempt3.log` — second attempt, landed in /top-app
- `runelite-tablet/docs/logs/slice5-pathA-60s-varrock.log` — 60s in-game counters
- `runelite-tablet/docs/logs/slice5-pathA-runelite-launch.log` — on-device launch log (first run, /moderate)

## Code status at S76 end

- `TermuxProcessPin` + session-service integration: **landed on spike/direct-android-surface**, uncommitted
- `launch-runelite.sh` cpuset logging + bash fix: **landed in repo asset**, still uncommitted
- Scripts also pushed to device via adb workaround (every fresh process start re-deploys broken asset from APK — ScriptManager re-deploy bug tracked in _state.md "Discovered-but-not-fixed bugs")

## Recommended next step for S77

Don't write code. Spend the session on **B2 — measure where the JVM waits**. Until we know the top syscall-wait sink, any larger refactor is guessing.

# Slice 5 / Path B — `RLT_PROOT_SECCOMP=1` A/B Result

**Date**: 2026-04-17
**Session**: 77
**Device**: R52X90378YB (Samsung Tab S10 Ultra, non-rooted, Android 16)
**Proot version**: 5.1.0 (Termux-shipped)

## Hypothesis under test

From `slice5-jvm-wait-analysis.md`: RuneLite's `Client` render thread is limited by proot's pure-ptrace syscall interception (3945 nonvol ctxt-switches/s on a thread doing ~2000 syscalls/s). Switching proot to seccomp-bpf mode (`PROOT_NO_SECCOMP=0`, enabled via the existing `RLT_PROOT_SECCOMP=1` flag) should drop per-syscall overhead ~10× and lift scene FPS from 12 toward 60+.

## Method

1. Modified on-device `launch-runelite.sh` default: `"${RLT_PROOT_SECCOMP:-0}"` → `"${RLT_PROOT_SECCOMP:-1}"`.
2. Sent SIGTERM to the live launch-runelite.sh parent (PID 16618) — cleanly cascades to JVM, virgl, proot.
3. Tapped Launch via `input tap 1480 1568`.
4. Watched logcat + `runelite-launch.log` for PTRACE_OPT confirmation and process-tree startup.
5. Reverted after observing failure.

## Result: **immediate launch failure**

`runelite-launch.log` confirms seccomp-bpf did engage:
```
PTRACE_OPT: seccomp-bpf mode enabled via PROOT_NO_SECCOMP=0
```

But proot-distro's Ubuntu login path died before the JVM started:
```
shell-init: error retrieving current directory: getcwd: cannot access parent directories: Function not implemented
env: 'bash': Function not implemented
RuneLite exited with code: 0
=== Shutting down RuneLite session ===
VIRGL_WATCHDOG: VirGL server died mid-session (PID=3947 exit=127) — GPU rendering will fail
```

`Function not implemented` is ENOSYS. That's seccomp-bpf's `SECCOMP_RET_ERRNO(ENOSYS)` returning for syscalls the filter doesn't handle. Two fatal points:

1. `getcwd()` ENOSYS — breaks every shell that runs in the proot after fork+exec (shell-init failure). Benign warning alone.
2. `env: 'bash': Function not implemented` — **the `execve()` syscall returned ENOSYS**. This means `/usr/bin/env` couldn't hand off to bash. proot-distro's login chain requires running `env` to set up the Ubuntu environment — it's one of the first things after proot enters the rootfs. If execve is ENOSYS'd here, nothing runs inside the rootfs. JVM never starts. VirGL's client dies. Session shuts down.

## Why seccomp-bpf broke execve here

proot's seccomp-bpf filter is supposed to set `SECCOMP_RET_TRACE` on path-translating syscalls (openat, execve, chdir, getcwd, readlink, etc.) — those fall through to proot's ptrace handler. Everything else gets `SECCOMP_RET_ALLOW` so it bypasses userspace proot entirely.

On this device, the Samsung/Android 16 kernel is (apparently) not delivering the `SECCOMP_RET_TRACE` events to proot for these syscalls, so the kernel default kicks in and the syscall gets ENOSYS'd. This can happen on kernels that:
- Disable `CONFIG_SECCOMP_FILTER_EXTENDED` tracepoints.
- Disable the `PTRACE_SECCOMP` capability for non-root processes.
- Use the "strict" seccomp mode vs classic filter mode.

On rooted Linux Debian/Ubuntu proot-distro works fine with seccomp-bpf; on Samsung's OneUI kernel + non-rooted execution, the TRACE path is blocked and the filter collapses to default-deny-ENOSYS for anything not explicitly ALLOW'd.

## What this does NOT change

The S77 diagnosis (see `slice5-jvm-wait-analysis.md`) is unaffected. The proot-ptrace overhead remains the FPS bottleneck — we just can't use the `RLT_PROOT_SECCOMP=1` env switch on this kernel.

## Next-step options (ordered cheapest → most invasive)

### B1a. Upgrade proot
Proot 5.1.0 is Termux-shipped (2021-ish). Upstream and Termux-packages maintain 5.3.x+ with various Android-kernel fixes. Try `pkg install proot` upgrade; re-test `RLT_PROOT_SECCOMP=1`. **Cost**: a few minutes. Upside unclear without trying.

### B1b. Custom seccomp whitelist via `PROOT_NO_SECCOMP` tuning
proot accepts `PROOT_NO_SECCOMP={0,1,2}`. Our test used 0 (filter enabled). Mode 2 (filter enabled, but skip TRACE for specific syscalls) may work but is poorly documented. Investigation required.

### B2. Replace Ubuntu proot-distro rootfs with a lighter-weight alternative
Instead of proot + Ubuntu, run RuneLite directly under Termux with Termux-packaged openjdk-17 + Mesa. Eliminates proot entirely for the hot path.
- **Cost**: moderate — need to verify Termux has all runtime deps, swap openjdk path in launch-runelite.sh.
- **Upside**: removes the entire 3945-ctxt-switches/s overhead.
- **Risk**: Termux's openjdk may not have the exact stack RuneLite expects (JavaFX? Swing? AWT on Termux uses X11, which should still work via our Xlorie).

### B3. `termux-exec` or raw `exec` bypass
Termux has a package `termux-exec` (LD_PRELOAD) that intercepts exec syscalls in userspace and avoids proot entirely. RuneLite's JVM could run in Termux-native with termux-exec patching the few path-dependent syscalls. Research required.

### B4. Patch proot source
Modify the seccomp filter in `third_party/` (if we're already shipping proot source) or build our own proot with a kernel-compatible filter. **Cost**: high. Last resort.

### B5. Accept the ceiling
Document 12 FPS as the architectural limit of "proot-distro Ubuntu + Mesa + VirGL on non-rooted Samsung kernel" and move on. User-visible impact: unplayable but runnable.

## Recommendation

Try B1a first (proot upgrade via pkg). ~5 minutes of effort. If 5.3.x still ENOSYS's, jump to B2 (Termux-native JVM) — that's the cleanest architectural fix and aligns with the root cause.

Do NOT try more ptrace/cpuset variants — we've ruled them out.

## Code state

- Repo `launch-runelite.sh`: unchanged (default still `RLT_PROOT_SECCOMP:-0`).
- On-device `launch-runelite.sh`: reverted to `:-0` after test.
- Pipeline state: session cleanly shut down; RLT app idle on MainActivity.

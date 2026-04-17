# Slice 5 / Option B — rlawt Bionic Compatibility Blocker (Session 77)

**Date**: 2026-04-17
**Session**: 77
**Status**: Option B blocked at Phase 1.2; rebuild rlawt required.

## Context

Option B = run RuneLite's JVM directly under Termux (Bionic libc), skipping proot-distro Ubuntu entirely, to eliminate the proot-ptrace per-syscall overhead identified in `slice5-jvm-wait-analysis.md` (3945 nonvol ctxt-switches/s on Client thread).

## What the feasibility audit proved

### Works out-of-the-box in Termux native
- **openjdk-21** (v 21.0.10) — installed via `pkg install openjdk-21 openjdk-21-x`. `java -version` clean.
- **Mesa 26.0.5** — installed. Provides libGL.so.1 (Bionic).
- **X11 client libs** — libx11, libxrandr, libxtst, libxext, libxrender, libxfixes, libxcomposite, libxdamage, libxi, libxcursor, libxinerama — all installed.
- **libjawt.so** — at `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib/libjawt.so` (Bionic).
- **virgl_test_server_android** — already Termux-native (pre-existing; runs outside proot in current architecture).

### The blocker: `net/runelite/rlawt/linux-aarch64/librlawt.so`

rlawt's bundled aarch64 native library is **glibc-linked**. `readelf -d` shows:

```
NEEDED    libjawt.so
NEEDED    libGL.so.1
NEEDED    libc.so.6                       ← glibc soname
NEEDED    ld-linux-aarch64.so.1           ← glibc dynamic linker
VERNEED   (section present, 2 entries, requires glibc-versioned symbols)
```

30 total undefined symbols. The breakdown:
- 5 libc primitives (`snprintf`, `memset`, `calloc`, `free`, `strstr`) — **100 % ABI-compatible with Bionic**
- 2 stack-protector symbols (`__stack_chk_fail`, `__stack_chk_guard`) — compatible
- 4 weak/init-related (`__cxa_finalize`, `__gmon_start__`, `_ITM_*`) — ignorable
- 11 glX/GL symbols (from libGL.so.1) — provided by Termux Mesa
- 6 X11 symbols (`XFree`, `XOpenDisplay`, `XSetErrorHandler`, `XDisplayString`, `XSync`, `XCloseDisplay`) — provided by Termux libX11
- 1 JAWT symbol (`JAWT_GetAWT`) — provided by openjdk-21-x

**Zero pthread symbols. Zero exotic glibc-only usage.** The library's actual runtime needs are Bionic-compatible; the problem is purely ELF-level metadata declaring glibc dependency.

## Why YOLO symlinks did not work

Android's Bionic dynamic linker enforces glibc-incompatibility at four distinct layers. Each one was probed:

| Attempt | Result |
|---|---|
| Symlink `libc.so.6 → /system/lib64/libc.so` + `ld-linux-aarch64.so.1 → /system/bin/linker64` + `LD_LIBRARY_PATH` | **Fail**: `/system/bin/linker64` path not in default linker namespace `permitted_paths`. |
| Re-symlink to paths INSIDE `permitted_paths` (`/apex/.../lib64/bionic/libc.so` / `libdl.so`) | **Fail**: `cannot find "ld-linux-aarch64.so.1" from verneed[0] in DT_NEEDED list` — bionic checks VERNEED *before* resolving NEEDED. |
| `patchelf --remove-needed ld-linux-aarch64.so.1` + `--replace-needed libc.so.6 libc.so` | **Fail**: VERNEED section still references the removed lib. patchelf 0.18.0 does not rewrite VERNEED when removing NEEDED. |
| `gobjcopy --remove-section=.gnu.version_r --remove-section=.gnu.version` | **Fail**: section stripped but dynamic-section entries (VERNEED, VERNEEDNUM, VERSYM) still point at offset — bionic reports `unsupported verneed[0] vn_version: 0 (expected 1)`. |

Further ELF surgery (hex-editing the dynamic section to drop VERNEED entries) is theoretically possible but fragile. The clean path is to rebuild.

## Decision

**Rebuild rlawt for Bionic via Android NDK** (new Task 21). The library is tiny (23 kB, 30 symbols, single-file-ish), rlawt source is on `github.com/runelite/rlawt`, and the fix is purely changing the toolchain used to compile the existing C source — not a code change.

Target deliverable: a `librlawt.so` whose `readelf -d` shows `NEEDED libc.so` (Bionic soname, no `.6`) and no VERNEED glibc version requirements. Dropped into `net/runelite/rlawt/linux-aarch64/librlawt.so` in a repackaged `rlawt-1.8-bionic.jar`, the existing `AWTContext.class` loader picks it up under Termux because Termux IS linux-aarch64 with Bionic libc (no changes to RL Java code).

Time estimate: **2-4 hours focused** (clone, NDK + CMake setup, cross-compile for aarch64-linux-android, verify against `MiniRlawtLoad.java`).

## Fallback if rebuild fails

Option A: fork Termux's proot 5.1.107-70, patch the seccomp-bpf filter to handle Samsung OneUI kernel's missing `SECCOMP_RET_TRACE` path. Different risk profile; tracked as Task 20.

## Deferred / rejected

- LD_LIBRARY_PATH tricks: rejected — layered ABI checks defeat.
- Full glibc environment via Termux's `glibc-repo`: rejected — no `openjdk-21-glibc` package exists; would need to build JDK from source.
- Linking rlawt against `gcompat` shim: rejected — would only help libc; doesn't address ld-linux-aarch64.so.1 or VERSYM checks.

## Artifacts (session 77)

- `/data/data/com.termux/files/home/rlawt-test/MiniRlawtLoad.java` — on-device smoke test for native library loading.
- `/data/data/com.termux/files/home/bionic-compat/` — symlink dir, YOLO attempt scaffold (can be deleted).
- `runelite-tablet/docs/logs/slice5-jvm-wait-60s.log` — raw sampler data (still valid).
- `runelite-tablet/docs/logs/slice5-jvm-wait-analysis.md` — diagnosis (unchanged).
- `runelite-tablet/docs/logs/slice5-seccomp-ab.md` — RLT_PROOT_SECCOMP=1 A/B (unchanged).

# Adversarial Review: VirGL Rendering Test Pipeline

**Input**: `.claude/specs/2026-03-09-virgl-test-pipeline-spec.md`
**Date**: 2026-03-09
**Affected Packages**: shell (new scripts only), no existing packages modified

## MUST-FIX (Blocks Implementation)

### 1. `adb push` Cannot Write to Termux Home Directory
- **Category**: Architecture / Constraint Violation
- **Location**: Deployment section — `deploy.sh`
- **Issue**: `adb push` writes as the `shell` user, which has no permission to Termux's data directory (`/data/data/com.termux/files/home/`). The existing codebase solves this by deploying via TermuxCommandRunner stdin.
- **Constraint**: Cross-UID file access impossible (termux-constraints Rule #3)
- **Recommendation**: Deploy via `adb push` to `/data/local/tmp/gl-tests/` (world-writable), then `adb shell run-as com.termux sh -c 'cp -r /data/local/tmp/gl-tests ~/gl-tests/'`. Or use a loop of `adb shell run-as com.termux sh -c 'cat > path' < local_file`.

### 2. `run-as com.termux sh -c` Doesn't Set Up Termux Environment
- **Category**: Architecture / Error Handling
- **Location**: Execution section
- **Issue**: `run-as com.termux sh -c` spawns a bare shell without Termux's `$PATH`, `$PREFIX`, or `$HOME`. `proot-distro` won't be found. Documented in MEMORY.md: "proot-distro commands fail via run-as (PATH not set up for proot)".
- **Recommendation**: Use `adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -l -c '...'"` (login shell sources Termux profile). Or have scripts self-bootstrap: `export PREFIX=/data/data/com.termux/files/usr; export PATH=$PREFIX/bin:$PATH`.

### 3. `dlopen()` for Shim A/B Testing Won't Intercept Already-Resolved GL Symbols
- **Category**: Architecture
- **Location**: Module 4 and Testing Protocol sections
- **Issue**: LD_PRELOAD shims work by inserting into the dynamic linker order *before* the real library. `dlopen()` at runtime does NOT retroactively intercept already-resolved symbols. By the time Module 4 calls `dlopen()`, `glClearDepth` etc. are already bound to real addresses.
- **Recommendation**: Run Module 4 as three separate sub-processes: `LD_PRELOAD=shim.so ./gl_test_harness --module 4a`. This tests the actual deployment mechanism (which is how the shim would be used with RuneLite).

### 4. `.gitattributes` Scope for C/H Files Not Covered
- **Category**: Completeness
- **Location**: New Files section
- **Issue**: Existing root `.gitattributes` has `*.sh text eol=lf` (covers shell scripts). But `.c` and `.h` files are NOT covered. Spec says ".gitattributes update" without specifying which file or patterns.
- **Recommendation**: Add to root `.gitattributes`: `gl-tests/src/*.c text eol=lf` and `gl-tests/src/*.h text eol=lf`.

### 5. `DEBIAN_FRONTEND=noninteractive` Missing for proot apt-get
- **Category**: Constraint Violation
- **Location**: `install-deps.sh`, `install-piglit.sh` (implied)
- **Issue**: Shell constraints require `DEBIAN_FRONTEND=noninteractive` for apt-get in no-PTY mode. Without it, debconf prompts hang. Existing codebase uses this consistently.
- **Recommendation**: Specify that all apt-get calls inside proot use `DEBIAN_FRONTEND=noninteractive`.

### 6. Results Path Mismatch — Proot Rootfs vs Termux Home
- **Category**: Architecture
- **Location**: Results section and Pulling Results
- **Issue**: Inside proot, `~/` resolves to `/root/` (physically at `...installed-rootfs/ubuntu/root/`). But the pull command runs under `run-as com.termux` where `~/` is Termux's home. The tar command will fail — directory not found.
- **Recommendation**: Have `run-tests.sh` copy results from proot rootfs to Termux's `$PREFIX/tmp/` after test run (accessible via `--shared-tmp`). Pull from there.

## SHOULD-CONSIDER (Advisory)

### 1. `MESA_NO_ERROR=1` Must Be Explicitly Unset During Testing
- **Category**: Architecture
- **Issue**: The spec's philosophy is "no silent failures" but doesn't explicitly state `MESA_NO_ERROR` must NOT be set. This env var is what caused the original silent failure.
- **Recommendation**: Add `unset MESA_NO_ERROR` to the instrumentation section. Call out that this is intentionally opposite of production.

### 2. `set -euo pipefail` Strategy for Proot-Entering Scripts
- **Category**: Constraint Violation
- **Issue**: Scripts entering proot face unreliable exit codes. Spec doesn't clarify which scripts use `-e` and which don't.
- **Recommendation**: `deploy.sh` uses `set -euo pipefail`. Scripts entering proot use `set -uo pipefail` (omit `-e`) with marker-based verification.

### 3. `apt-get update` Without `|| true` in Proot
- **Category**: Constraint Violation
- **Issue**: `apt-get update` returns non-zero without `gpgv`. Install scripts don't mention this.
- **Recommendation**: Use `apt-get update -qq || true` per existing pattern.

### 4. VirGL Server Lifecycle Conflict with Running App
- **Category**: State Management
- **Issue**: If RuneLite is already running, its VirGL server occupies the socket. Starting a second fails.
- **Recommendation**: Add pre-flight check: `pgrep -f virgl_test_server` → warn/fail. State that tests cannot run concurrently with RuneLite.

### 5. Symlink `latest` May Not Work Across Proot/adb Boundary
- **Category**: Architecture
- **Issue**: Proot symlinks use `.l2s` emulation. `adb pull` / `run-as` may not resolve them.
- **Recommendation**: Write latest path to a `LATEST` text file instead of symlink.

### 6. `environment.json` May Leak Sensitive Environment Variables
- **Category**: Data Leakage / Logging
- **Issue**: Dumping all env vars could expose API keys, tokens from other tools, or usernames if results are shared.
- **Recommendation**: Use an allowlist of GL/Mesa/VirGL-related variables only. Add `.gitignore` in `gl-tests/results/`.

### 7. `adb shell setprop` Modifies Global System Properties Without Cleanup
- **Category**: IPC Security
- **Issue**: `debug.angle.markers`, `debug.vulkan.layers` etc. are device-global and persist until reboot.
- **Recommendation**: Save original values, restore in cleanup/trap handler on script exit.

### 8. No Disk Space Check Before Piglit Build
- **Category**: Error Handling
- **Issue**: Piglit build can consume 1-2 GB. No space check documented.
- **Recommendation**: Check `df -k` for 2GB free before Piglit build, per existing pattern.

### 9. `stb_image_write.h` Vendored Without Version/License
- **Category**: Completeness
- **Issue**: No commit hash, version, or license attribution.
- **Recommendation**: Document source URL, commit hash, and license (public domain / MIT) in a header comment.

### 10. Idempotency Not Explicit for Install Scripts
- **Category**: Constraint Violation
- **Issue**: `install-deps.sh` and `install-piglit.sh` don't describe skip-if-exists behavior.
- **Recommendation**: Check for existing installations before running. Use marker files.

### 11. `dlopen()` Path Security — Use Absolute Paths
- **Category**: File Permissions
- **Issue**: If `dlopen()` uses relative paths, a malicious `.so` in `LD_LIBRARY_PATH` could be loaded.
- **Recommendation**: Always use absolute paths. `chmod 755` compiled `.so` files.

### 12. `MESA_SHADER_CAPTURE_PATH` Should Target Results Directory
- **Category**: File Permissions
- **Issue**: Spec doesn't specify exact path. Could scatter files outside results.
- **Recommendation**: Set to `results/run-YYYYMMDD-HHMMSS/shaders/` with `chmod 700`.

### 13. `df -h` vs `df -k` in Termux Layer
- **Category**: Constraint Violation
- **Issue**: `df -h` fine inside proot Ubuntu, but `df -m` broken in Termux toybox.
- **Recommendation**: Clarify which layer each `df` call runs in. Use `df -k` in Termux.

## Summary

- **MUST-FIX**: 6 findings
- **SHOULD-CONSIDER**: 13 findings
- **Verdict**: **REVISE** (1-3 MUST-FIX threshold exceeded)

The 6 MUST-FIX items are all practical/deployment issues — the core design is sound. The most impactful are #1 (adb push), #2 (Termux PATH), #3 (dlopen vs LD_PRELOAD), and #6 (results path). All have straightforward fixes documented above.

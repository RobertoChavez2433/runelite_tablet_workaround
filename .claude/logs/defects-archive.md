# Defects Archive

Older/resolved defects rotated from per-feature files in `.claude/defects/`.

---

### [SHELL] 2026-02-22: openjdk-11-jdk-headless missing AWT/X11 libs — FIXED
**Pattern**: RuneLite launcher crashes with `UnsatisfiedLinkError: libawt_xawt.so`. Headless JDK excludes AWT/Swing/X11.
**Fix**: Changed `openjdk-11-jdk-headless` to `openjdk-11-jdk` in setup-environment.sh.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [SHELL] 2026-02-22: X11 socket not visible inside proot — bind-mount missing — FIXED
**Pattern**: `AWTError: Can't connect to X11 window server using ':0'`. Proot's `/tmp` is isolated from Termux's `$PREFIX/tmp`.
**Fix**: Added `--bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"` to `proot-distro login` in launch-runelite.sh.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [SHELL] 2026-02-22: RuneLite launcher JvmLauncher child process dies in proot — FIXED
**Pattern**: JvmLauncher spawns client via ProcessBuilder, but proot's `--kill-on-exit` kills the child when launcher exits.
**Fix**: Run `net.runelite.client.RuneLite` directly via `exec java -cp ...` bypassing the launcher.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [SHELL] 2026-02-22: proot-distro install cleans up rootfs on non-zero exit — FIXED
**Pattern**: `proot-distro install ubuntu` returns non-zero due to `/proc/self/fd/1,2` warnings. Rootfs cleaned up.
**Fix**: Manual rootfs extraction fallback with post-extraction DNS/hosts/env config.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [ANDROID] 2026-02-22: SetupOrchestrator isSuccess too strict for proot commands — FIXED
**Pattern**: `isSuccess` checks `exitCode == 0 && error == null`. Proot commands return non-zero exit codes due to harmless `/proc/self/fd` binding warnings. This caused setup scripts to be treated as failed despite completing all steps.
**Fix**: Added success marker check — looks for `"=== Setup complete ==="` in stdout as alternative to exitCode == 0.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [ANDROID] 2026-02-22: TermuxResultService onDestroy clears static pendingResults — FIXED
**Pattern**: `onDestroy()` cancelled all deferreds in static `pendingResults` map. Static fields outlive service instances.
**Fix**: Removed deferred cancellation from `onDestroy()`. Added `stopSelfIfIdle()`.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/termux/TermuxResultService.kt

### [SHELL] 2026-02-22: Manual rootfs extraction skips post-install config — FIXED
**Pattern**: Manual tar extraction missing resolv.conf (DNS), hosts, environment. apt-get hangs on DNS.
**Fix**: Script writes resolv.conf, hosts, environment after manual extraction.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [SHELL] 2026-02-22: DEBIAN_FRONTEND=noninteractive required for apt-get in no-PTY mode — FIXED
**Pattern**: debconf prompts hang even with `-y` when stdin is /dev/null.
**Fix**: Added `env DEBIAN_FRONTEND=noninteractive` before bash -c in proot-distro login calls.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [SHELL] 2026-02-22: Termux X11 socket at $PREFIX/tmp, not /tmp — FIXED
**Pattern**: X11 socket at `$PREFIX/tmp/.X11-unix/X0`, not `/tmp/.X11-unix/X0`.
**Fix**: Changed socket path to use `$PREFIX/tmp`.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

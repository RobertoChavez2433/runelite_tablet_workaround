# Defects Archive

Older/resolved defects rotated from per-feature files in `.claude/defects/`.

---

## Rotated 2026-02-23 (Session 26)

### [TERMUX] 2026-02-22: Env var injection via command string prefix doesn't work with Termux execve
**Pattern**: Prepending `export VAR=val; bash script.sh` to the Termux RUN_COMMAND `commandPath` string doesn't work — Termux passes `commandPath` as the literal executable path to `execve()`, not to a shell. Credentials never reach the script.
**Prevention**: Pass credentials via a temp file in app-private storage. Script sources and immediately `rm -f`s the file. Never pass secrets as command-line arguments (also visible in `ps`).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

---

## Rotated 2026-02-23 (Session 25)

### [ANDROID] 2026-02-22: startActivity() from background context blocked on Android 10+
**Pattern**: Calling `context.startActivity()` from `SetupOrchestrator` (which holds applicationContext) to bring Termux:X11 to the foreground. On Android 10+ this is silently dropped if the app is not in the foreground — no exception thrown.
**Prevention**: Route all Activity starts through `SetupActions.launchIntent()` callback (goes through the Activity, which IS in the foreground when the user taps Launch).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

---

### [UX] 2026-02-22: RuneLite window is tiny — not filling tablet screen — DESIGNED
**Pattern**: RuneLite renders at 1038x503 on a 2960x1711 X11 desktop. No window manager = windows open at default size.
**Root cause**: Bare X11 with no window manager. OSRS defaults to 765x503 + sidebar.
**Fix**: Install openbox WM in proot, configure auto-maximize + no decorations.
**Status**: DESIGNED — ready to implement.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

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

### [UX] 2026-02-22: Termux/Termux:X11 workflow confusing — user must manually switch apps — DESIGNED
**Pattern**: User must manually switch from the RuneLite Tablet app to Termux:X11 after tapping Launch. No in-app guidance; context switch is unintuitive.
**Fix**: Kotlin sends CHANGE_PREFERENCE broadcast (fullscreen, no keyboard bar). Shell script polls X11 socket then runs `am start` to bring Termux:X11 to foreground.
**Status**: DESIGNED — implemented in Slice 4+5.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

---

## Rotated 2026-02-23 (Session 29)

### [ANDROID] 2026-02-23: Chrome Custom Tabs onNavigationEvent URL extra is unreliable
**Pattern**: `CustomTabsCallback.onNavigationEvent(NAVIGATION_STARTED)` does not guarantee URL in extras Bundle. Session callback may not attach if Custom Tab launched before `onCustomTabsServiceConnected`.
**Prevention**: Use localhost `ServerSocket` or `jagex:` intent scheme capture — fully under app control, no Chrome dependency.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/AuthRedirectCapture.kt

### [SECURITY] 2026-02-23: Kotlin data class toString() leaks sensitive fields by default
**Pattern**: Auto-generated `toString()` includes ALL fields. Accidental logging exposes plaintext secrets.
**Prevention**: Always add `override fun toString() = "ClassName([REDACTED])"` to data classes with sensitive fields.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt

---

## Rotated 2026-02-23 (Session 28)

### [WINDOWS] 2026-02-23: CRLF line endings in shell scripts break shebang on Termux
**Pattern**: Windows git auto-converts LF to CRLF on checkout. Shell scripts deployed to Termux via `cat > file` retain `\r` in the shebang line. Kernel returns ENOENT.
**Prevention**: `.gitattributes` with `*.sh text eol=lf`, defensive `replace("\r", "")` in ScriptManager.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/ScriptManager.kt, @.gitattributes

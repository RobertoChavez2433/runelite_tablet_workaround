# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [SECURITY] 2026-02-23: Kotlin data class toString() leaks sensitive fields by default
**Pattern**: Kotlin `data class` auto-generates `toString()` including ALL fields. If a data class holds tokens, credentials, or auth codes, any accidental logging (crash reports, exception interpolation, debug logs) exposes plaintext secrets.
**Prevention**: Always add `override fun toString() = "ClassName([REDACTED])"` to any data class holding sensitive fields. Check: JagexCredentials, TokenResponse, AuthCodeResult.Success, OAuthException.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt

### [SECURITY] 2026-02-23: OAuth2 error response bodies can contain tokens
**Pattern**: When token exchange fails, the HTTP error body may echo back the submitted `code`, `refresh_token`, or partial credentials. Logging the full error body exposes these to logcat and persistent log files.
**Prevention**: Always truncate error bodies (max 200 chars) and sanitize token-like patterns before logging. Never pass raw response bodies to exception classes that might reach UI or logs.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

### [TERMUX] 2026-02-22: Env var injection via command string prefix doesn't work with Termux execve
**Pattern**: Prepending `export VAR=val; bash script.sh` to the Termux RUN_COMMAND `commandPath` string doesn't work — Termux passes `commandPath` as the literal executable path to `execve()`, not to a shell. Credentials never reach the script.
**Prevention**: Pass credentials via a temp file in app-private storage. Script sources and immediately `rm -f`s the file. Never pass secrets as command-line arguments (also visible in `ps`).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [SHELL] 2026-02-22: Writing success marker after proot call, not after positive verification
**Pattern**: Writing the marker file immediately after `proot-distro login -- <cmd>` succeeds (exit 0) — but proot returns non-zero even on successful operations due to `/proc/self/fd` warnings, so the script may have exited before writing the marker, OR the marker is written after a silently failed operation.
**Prevention**: Always wrap proot calls with `|| true`, then run a positive verification (e.g., `which java`, file existence check), and write the marker ONLY after the verification passes.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [ANDROID] 2026-02-22: startActivity() from background context blocked on Android 10+
**Pattern**: Calling `context.startActivity()` from `SetupOrchestrator` (which holds applicationContext) to bring Termux:X11 to the foreground. On Android 10+ this is silently dropped if the app is not in the foreground — no exception thrown.
**Prevention**: Route all Activity starts through `SetupActions.launchIntent()` callback (goes through the Activity, which IS in the foreground when the user taps Launch).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [UX] 2026-02-22: RuneLite window is tiny — not filling tablet screen — DESIGNED
**Pattern**: RuneLite renders at 1038x503 on a 2960x1711 X11 desktop. No window manager = windows open at default size.
**Root cause**: Bare X11 with no window manager. OSRS defaults to 765x503 + sidebar.
**Fix**: Install openbox WM in proot, configure auto-maximize + no decorations.
**Status**: DESIGNED — ready to implement.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [UX] 2026-02-22: Termux/Termux:X11 workflow confusing — user must manually switch apps — DESIGNED
**Pattern**: User must manually switch from the RuneLite Tablet app to Termux:X11 after tapping Launch. No in-app guidance; context switch is unintuitive.
**Root cause**: No auto-switch logic. No fullscreen/immersive configuration.
**Fix**: Kotlin sends CHANGE_PREFERENCE broadcast (fullscreen, no keyboard bar). Shell script polls X11 socket then runs `am start` to bring Termux:X11 to foreground.
**Status**: DESIGNED — ready to implement.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

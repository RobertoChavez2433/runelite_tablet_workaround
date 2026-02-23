# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

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

### [SHELL] 2026-02-22: setupScriptRan guard silently routes refactored steps to old monolithic script
**Pattern**: `SetupOrchestrator` has a `setupScriptRan: Boolean` guard in `executeStep()` that returns early for steps 4-6 after the first script run, routing them all to `runSetupScript()` (the old monolithic script). If this field is not explicitly deleted during the Slice 2 refactor, modular script calls are silently bypassed.
**Prevention**: When refactoring to modular scripts, explicitly delete `setupScriptRan: Boolean` field AND `runSetupScript()` function — don't just add new dispatch logic around them.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [UX] 2026-02-22: RuneLite window is tiny — not filling tablet screen — DESIGNED
**Pattern**: RuneLite renders at 1038x503 on a 2960x1711 X11 desktop. No window manager = windows open at default size.
**Root cause**: Bare X11 with no window manager. OSRS defaults to 765x503 + sidebar.
**Fix**: Install openbox WM in proot, configure auto-maximize + no decorations. Design at `.claude/plans/2026-02-22-display-and-launch-ux-design.md`.
**Status**: DESIGNED — ready to implement.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [UX] 2026-02-22: Termux/Termux:X11 workflow confusing — user must manually switch apps — DESIGNED
**Pattern**: User must manually switch from the RuneLite Tablet app to Termux:X11 after tapping Launch. No in-app guidance; context switch is unintuitive.
**Root cause**: No auto-switch logic. No fullscreen/immersive configuration.
**Fix**: Kotlin sends CHANGE_PREFERENCE broadcast (fullscreen, no keyboard bar). Shell script polls X11 socket then runs `am start` to bring Termux:X11 to foreground. Design at `.claude/plans/2026-02-22-display-and-launch-ux-design.md`.
**Status**: DESIGNED — ready to implement.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

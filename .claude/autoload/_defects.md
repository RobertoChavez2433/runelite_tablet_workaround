# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [SECURITY] 2026-02-24: shellEscape must cover ALL bash metacharacters including newlines
**Pattern**: Single-quote escaping (`'\''`) misses `$()`, backtick, and other expansions when file is `source`d. Newlines in credential values break out of quoted strings entirely, enabling shell injection.
**Prevention**: Use double-quote escaping for all 5 metacharacters (`\`, `"`, `$`, `` ` ``, `!`) PLUS strip `\n`, `\r`, `\0`. Or use `printf %q` (bash-native).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (shellEscape)

### [ANDROID] 2026-02-24: GeckoAuthActivity must handle process death — check savedInstanceState
**Pattern**: GeckoView sessions and `lateinit` vars cannot survive process death. Restored Activity crashes with `UninitializedPropertyAccessException`.
**Prevention**: Check `savedInstanceState != null` in `onCreate` and finish with error. Also add `FLAG_SECURE` to prevent login page appearing in recents.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt

### [ANDROID] 2026-02-24: EncryptedSharedPreferences corruption bricks credential storage permanently
**Pattern**: Power loss or disk corruption can break the encrypted prefs XML file. Next `create()` call throws `GeneralSecurityException` and returns null forever — user must uninstall app.
**Prevention**: On exception, delete the corrupted file and retry once. Guard with a `prefsRecreateAttempted` flag to prevent infinite loops.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt

### [ANDROID] 2026-02-24: resetSetup/runSetupForHealth must reset orchestrator + setupStarted flag
**Pattern**: `resetSetup()` clears stateStore but leaves orchestrator's `_permissionPhase`, `_awaitingPermissionCompletion`, and `failedStepIndex` stale. `runSetupForHealth()` doesn't reset `setupStarted` so `startSetup()` no-ops on re-entry.
**Prevention**: Add `orchestrator.resetState()` method and call it from resetSetup. Reset `setupStarted.set(false)` from runSetupForHealth.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt

### [ANDROID] 2026-02-24: Cross-app file access — app's private filesDir is not readable by Termux
**Pattern**: Writing a file to `context.filesDir` (`/data/user/0/com.runelitetablet/files/`) and passing the path to Termux. Termux runs as a different UID and cannot read it — `[ -f "$path" ]` silently fails.
**Prevention**: Deploy files to Termux via `TermuxCommandRunner.execute()` with stdin (same pattern as script deployment). File lands in Termux's home dir where it's accessible.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (performLaunch)

### [SHELL] 2026-02-24: Termux processes survive Android app force-stop — must explicitly kill
**Pattern**: `am force-stop com.runelitetablet` only kills our app. Termux is a separate process — Java/proot/openbox/PulseAudio/X11 keep running as zombies.
**Prevention**: Run `cleanup_previous()` at launch script start that pkills all known process patterns. Add comprehensive EXIT trap for clean shutdown.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [SECURITY] 2026-02-24: OkHttp .execute() blocks IO thread on coroutine cancellation — use executeCancellable
**Pattern**: `httpClient.newCall(request).execute()` is a blocking call that does not respond to coroutine cancellation.
**Prevention**: Use `suspendCancellableCoroutine` + `call.enqueue()` + `invokeOnCancellation { call.cancel() }`.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

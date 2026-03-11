# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [ANDROID] 2026-03-08: Auth refresh only does Step 1/3 — game session not recreated
**Pattern**: `refreshTokens()` renews Step 1 launcher tokens but skips Step 2 (consent browser flow) and Step 3 (createGameSession). `JX_SESSION_ID` expires server-side (~12 days). Refresh "succeeds" but game login silently fails because session is stale and `JX_ACCESS_TOKEN` is the wrong token (Step 1 launcher, not game).
**Prevention**: Before launch, validate session via GET `/accounts?sessionId=...`. On 401, clear credentials and auto-trigger full GeckoView re-auth flow.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt (refreshTokens), @.claude/plans/2026-03-08-auth-session-refresh-fix.md

### [SHELL] 2026-02-24: All double quotes inside bash -c "..." blocks must be escaped
**Pattern**: Unescaped `"` inside a `bash -c "..."` block terminates the outer string. The outer shell then parses remaining text as commands — `(` becomes a subshell start and causes syntax errors.
**Prevention**: Every `"` inside a `bash -c "..."` block must be `\"`. Use `awk | grep -n '"' | grep -v '\\"'` to audit.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (lines 282-450)

### [ANDROID] 2026-02-24: reconcileWithMarkers ABSENT must clear stateStore, not just step UI status
**Pattern**: `reconcileWithMarkers()` downgrades step status to Pending but doesn't clear `stateStore.isCompleted()` flag. The `executeStep()` function checks stateStore first and skips — step never re-runs.
**Prevention**: Always call `stateStore.clearCompleted(key)` alongside `updateStepStatus(index, Pending)` in ABSENT branch.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt (reconcileWithMarkers)

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

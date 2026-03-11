# Auth Defects

Max 5 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [ANDROID] 2026-03-09: Step 1 OAuth tokens discarded for Jagex accounts during Step 2 consent
**Pattern**: `GeckoAuthActivity.handleStep2Redirect()` returned `accessToken=null, refreshToken=null`. Step 1 tokens obtained during `exchangeCodeForTokens` were not saved before loading Step 2 consent URL. `SetupViewModel.handleAuthResult` for Jagex accounts never called `storeTokens()`. Env file had no `JX_ACCESS_TOKEN`.
**Prevention**: Save Step 1 `TokenResponse` fields in GeckoAuthActivity instance variables before Step 2. Pass them through `finishWithSuccess()`. ViewModel must call `storeTokens()` for ALL login providers (not just RuneScape legacy).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt, @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (handleAuthResult)

### [ANDROID] 2026-03-09: HTTP 400 invalid_grant not treated as NeedsLogin in refreshIfNeeded
**Pattern**: `refreshIfNeeded()` only treated HTTP 401 as `AuthResult.NeedsLogin`. HTTP 400 `invalid_grant` (dead/revoked refresh token) was treated as `NetworkError` — continued with stale credentials instead of triggering GeckoView re-auth.
**Prevention**: Treat both HTTP 400 and 401 from token refresh as `NeedsLogin`. Both mean the refresh token is permanently dead and user must re-authenticate.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (refreshIfNeeded)

### [SHELL] 2026-03-09: repository2/ client jars never updated — launcher bypass freezes revision — D-AUTH-STALE-JARS P0
**Pattern**: `launch-runelite.sh` has two launch paths: Path A (`exec java -cp` from `repository2/` jars) and Path B (run `RuneLite.jar` launcher). Once `repository2/` is populated from the first launcher run, Path A ALWAYS fires — `RuneLite.jar` is never invoked again. The launcher is what auto-updates client jars; bypassing it freezes jars at the revision from initial setup. OSRS updates weekly (rev235→rev236 Feb 1, 2026). Stale jars cause `LOGGING_IN → LOGIN_SCREEN` with no error — the exact same symptom as auth failure. `update-runelite.sh` only updates `RuneLite.jar`, not client jars in `repository2/`. Normal RuneLite desktop users are never affected because they always run the launcher.
**Prevention**: On every launch, verify `repository2/` jar revision matches the current OSRS revision manifest before choosing Path A. If mismatch or manifest unreachable, fall through to Path B (launcher) to force update. Alternatively, delete `repository2/` cache on `update-runelite.sh` so the next launch always goes through Path B.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (Path A / Path B selection), @runelite-tablet/app/src/main/assets/scripts/update-runelite.sh

### [ANDROID] 2026-03-09: Jagex accounts receive 5 env vars instead of 3 — token confusion — D-AUTH-TOKEN-CONFUSION P1
**Pattern**: `launch-runelite.sh` and `SetupViewModel.performLaunch()` pass all 5 env vars (`JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`, `JX_ACCESS_TOKEN`, `JX_REFRESH_TOKEN`) for Jagex accounts. Only 3 are correct: `JX_ACCESS_TOKEN` and `JX_REFRESH_TOKEN` belong to legacy RuneScape accounts only. For Jagex accounts the Step 1 `access_token` scope is `gamesso.token.create` — it creates game sessions, it is NOT the game session token. Passing it as `JX_ACCESS_TOKEN` is incorrect and may cause the client to attempt legacy-account auth paths. NOTE: Session 46 diagnosed this as "game server rejects login — Step 1 OAuth access_token may not be correct for game auth." That diagnosis was correct in direction but incomplete: the primary failure was stale jars (D-AUTH-STALE-JARS); this is a secondary issue to fix after jars are updating correctly.
**Prevention**: Gate env var writing on account type. Jagex accounts: write only `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`. Legacy RuneScape accounts: write all 5. Verify against RuneLite source `JagexAccountAuthenticator` for the exact env var contract.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (env export block), @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (performLaunch)

### [SHELL] 2026-03-09: Hardcoded launcher version pin is stale — D-AUTH-LAUNCHER-PIN P2
**Pattern**: `launch-runelite.sh` passes `-Drunelite.launcher.version=2.7.6` hardcoded. Current launcher version is 2.7.7. Jagex Launcher v2.3.0 (Jan 27, 2026) added unspecified security improvements; v2.4.0 (Feb 26, 2026) dropped legacy OSRS/RS client detection; legacy Java Client killed Jan 28, 2026. A stale version pin may cause the client to report an outdated launcher to Jagex servers or miss version-gated behavior.
**Prevention**: Read launcher version dynamically from `RuneLite.jar` manifest or a pinned constant updated by `update-runelite.sh`. Do not hardcode; instead derive from the actual `RuneLite.jar` in use (e.g., `unzip -p RuneLite.jar META-INF/MANIFEST.MF | grep Implementation-Version`).
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (-Drunelite.launcher.version flag)

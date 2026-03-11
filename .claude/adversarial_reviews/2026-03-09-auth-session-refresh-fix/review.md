# Adversarial Review: Auth Session Refresh Fix

**Input**: `.claude/plans/2026-03-08-auth-session-refresh-fix.md`
**Date**: 2026-03-09
**Affected Packages**: auth, setup, ui

## MUST-FIX (Blocks Implementation)

### 1. No auto-resume mechanism after re-auth
- **Category**: Architecture
- **Location**: Plan section "Auto-Trigger Auth Flow" (step 4) and "UX Flow"
- **Issue**: The plan says "On auth completion callback -> resume launch flow" and "Auth complete -> Launch proceeds automatically", but defines no mechanism for this. Currently `handleAuthResult()` navigates to `AppScreen.Launch` and stops — the user must tap "Launch RuneLite" again. There is no `pendingLaunchAfterAuth` flag, no auto-call to `launchRuneLite()` or `performLaunch()` from `handleAuthResult()`, and grep confirms no such pattern exists in the codebase today.
- **Constraint**: The plan's own UX flow promises seamless re-auth-then-launch, but the implementation details omit the resume mechanism entirely.
- **Recommendation**: Add a `pendingLaunchAfterAuth: AtomicBoolean` flag. Set it `true` in `performLaunch()` before triggering re-auth. In `handleAuthResult()`, after successful auth + Step 3, check the flag — if set, call `launchRuneLite()` (which redoes update check + health check + performLaunch) or `performLaunch()` directly (skipping redundant checks). Clear the flag on cancel/error/success. Document which approach is chosen.

### 2. `validateSession()` boolean return cannot distinguish expired from network error
- **Category**: Architecture / Error Handling
- **Location**: Plan section "Session Validation (New)" — `validateSession(sessionId): Boolean`
- **Issue**: The method signature returns `Boolean` (true=200, false=401/403). But if the network is unreachable, an `IOException` would either propagate uncaught or be swallowed into `false`. If `false`, the caller clears credentials and forces re-auth — destroying valid credentials because of a transient network issue. The plan's own edge case #2 says "Network error during validation — Don't clear creds", but the `Boolean` API makes this impossible to implement correctly at the call site.
- **Constraint**: Auth constraint #5 requires "on 401, clear credentials" — but only on 401, not on network errors. The return type must encode the difference.
- **Recommendation**: Return a sealed class (e.g., `SessionValidation { Valid, Expired, NetworkError(e) }`) or throw `IOException` on network error and only return `Boolean` for 200 vs 401. The caller in `performLaunch()` must handle each case differently: `Valid` -> proceed, `Expired` -> clear + re-auth, `NetworkError` -> log + continue (match existing `refreshIfNeeded` NetworkError behavior).

### 3. Missing `LaunchState` for session validation UI
- **Category**: Completeness
- **Location**: Plan section "UX Flow" — "Checking session..." status, and "Key Implementation Details"
- **Issue**: The plan describes showing "Checking session..." to the user, but doesn't define a new `LaunchState` variant. The existing `LaunchState` sealed class has: `Idle`, `CheckingUpdate`, `Updating`, `CheckingHealth`, `RefreshingTokens`, `Launching`, `Failed`. There is no `ValidatingSession` state. Without it, the UI cannot show the "Checking session..." message — it would either reuse `RefreshingTokens` (misleading) or have no visual feedback during the validation HTTP call.
- **Recommendation**: Add `LaunchState.ValidatingSession` to the sealed class. Set it in `performLaunch()` after refresh completes and before calling `validateSession()`. The UI layer (`LaunchScreen.kt` or equivalent) needs a corresponding case to display "Checking session...".

## SHOULD-CONSIDER (Advisory)

### 1. Method naming mismatch: `clearAll()` vs `clearCredentials()`
- **Category**: Consistency
- **Issue**: The plan references `credentialManager.clearAll()` in multiple places, but the actual method in `CredentialManager.kt:194` is `clearCredentials()`. The plan's "Files to Modify" table says "Verify/add `clearAll()` method", implying it might not exist — but a method with different naming already does.
- **Risk**: An implementer might create a redundant `clearAll()` alongside the existing `clearCredentials()`, or waste time looking for a method that already exists under a different name.
- **Recommendation**: Update the plan to reference `clearCredentials()` consistently, and note that it already exists and handles both in-memory and persisted token cleanup.

### 2. Wasted Step 1 token refresh when session is expired
- **Category**: Performance
- **Issue**: The plan's ordering is: `refreshIfNeeded()` -> `validateSession()`. If the session is expired (the primary scenario this plan fixes), the Step 1 token refresh completes successfully but is immediately wasted — credentials are cleared for re-auth. This adds ~1-2 seconds of unnecessary latency to the re-auth path.
- **Risk**: Minor UX delay on the re-auth path. Not a correctness issue.
- **Recommendation**: Consider validate-first ordering: `validateSession()` -> `refreshIfNeeded()` -> deploy. If session is expired, skip refresh entirely and go straight to re-auth. However, this changes the logic for the "no session ID" case (fresh install), so evaluate whether the complexity is worth the ~1s gain.

### 3. RuneScape (legacy) accounts may not have a `JX_SESSION_ID` to validate
- **Category**: Edge Case
- **Issue**: Legacy "runescape" login_provider accounts skip Step 2 + Step 3 entirely. In `handleAuthResult()`, the runescape path calls `storeTokens()` but never `storeGameSession()`. This means `KEY_SESSION_ID` is never set, so `hasCredentials()` returns false. The plan's validation logic sits inside the `if (hasCredentials)` block, so it would be skipped for these accounts — which is likely correct but is never explicitly acknowledged.
- **Risk**: If the auth flow is ever changed to store session IDs for legacy accounts, the validation would incorrectly try to validate them against the Jagex session endpoint. Low risk today.
- **Recommendation**: Add a comment in the plan and implementation noting that session validation only applies to Jagex accounts (which have a `JX_SESSION_ID`). Legacy RuneScape accounts bypass validation via the existing `hasCredentials()` gate.

### 4. `performLaunch()` triggers re-auth but needs `SetupActions` bound
- **Category**: Edge Case
- **Issue**: To launch GeckoAuthActivity, the code calls `orchestrator.actions?.launchAuthActivity(intent)`. `actions` is bound via `bindActions()` in `onResume` and unbound in `onPause`. If the user triggers launch and the Activity is paused between token refresh and session validation (unlikely but possible via split-screen or notification shade), `actions` would be null and the re-auth launch would fail silently.
- **Risk**: Very low — the launch flow runs in a single coroutine and the Activity would need to pause mid-execution. The existing `startLogin()` already has this same pattern and handles the null case by showing `AuthError`.
- **Recommendation**: Ensure the re-auth trigger in `performLaunch()` mirrors `startLogin()`'s null-check pattern: if `actions` is null, set `LaunchState.Failed("Activity not available")` rather than failing silently.

## Summary
- MUST-FIX: 3 findings
- SHOULD-CONSIDER: 4 findings
- Verdict: **REVISE** — The 3 MUST-FIX items are all solvable within the existing architecture. #1 (auto-resume) and #2 (tri-state validation) are the most important — without them, the implementation either breaks the promised UX or destroys credentials on network errors.

# Design: OAuth 2-Step Flow Rewrite

**Date**: 2026-02-23
**Status**: APPROVED
**Session**: 29
**Supersedes**: `2026-02-23-custom-tabs-auth-design.md` (wrong client_id, single-step assumption)

---

## Problem

Our `JagexOAuth2Manager.kt` uses the consent client_id (`1fddee4e-...`) for all OAuth steps. This is wrong — Jagex OAuth is a 2-step, 2-client-ID flow. Session 28 confirmed the error ("Sorry, something went wrong"). Session 29 on-device testing confirmed:

1. **Consent client standalone does NOT work** — returns `unsupported_response_type` for both `code` and `id_token code` response types
2. **`jagex:` scheme capture DOES work on Android Chrome** — `window.location = "jagex:..."` triggers our intent filter and delivers the full URI to `onNewIntent`
3. **Game session API calls are wrong** — current code passes accessToken as Bearer, but the real flow POSTs id_token to `/sessions` and uses sessionId as Bearer for `/accounts`

## Solution

Implement the correct 2-step, 2-client-ID flow matching all 3 reference implementations (aitoiaita, melxin, Bolt):

- **Step 1**: `com_jagex_auth_desktop_launcher` + Chrome Custom Tab + `jagex:` scheme capture
- **Step 2**: `1fddee4e-...` + Chrome Custom Tab + localhost server + forwarder HTML
- **Step 3**: Game session API calls with correct auth method

---

## Verified Parameters (from aitoiaita source + on-device testing)

### Step 1: Account Authentication (Launcher Client)

| Parameter | Value |
|-----------|-------|
| client_id | `com_jagex_auth_desktop_launcher` |
| redirect_uri | `https://secure.runescape.com/m=weblogin/launcher-redirect` |
| scope | `openid offline gamesso.token.create user.profile.read` |
| response_type | `code` |
| auth_url | `https://account.jagex.com/oauth2/auth` |
| token_url | `https://account.jagex.com/oauth2/token` |
| PKCE | S256, 64-byte random verifier |
| State | Random CSRF nonce |

### Step 2: Consent (Consent Client)

| Parameter | Value |
|-----------|-------|
| client_id | `1fddee4e-b100-4f4e-b2b0-097f9088f9d2` |
| redirect_uri | `http://localhost:<port>` |
| scope | `openid offline` |
| response_type | `id_token code` |
| auth_url | `https://account.jagex.com/oauth2/auth` |
| nonce | 48 random alphanumeric characters |
| PKCE | **NONE** |
| State | Random CSRF nonce |
| Returns | `id_token` + `code` in URL fragment (`#id_token=...&code=...`) |

### Step 3: Game Session APIs

| Endpoint | Method | Auth | Body |
|----------|--------|------|------|
| `/game-session/v1/sessions` | POST | None | `{"idToken": "<jwt>"}` |
| `/game-session/v1/accounts` | GET | Bearer `<sessionId>` | None |

### login_provider Branching

Step 1 id_token contains `login_provider` claim:
- `"jagex"` — Jagex account: proceed to Step 2, then Step 3
- `"runescape"` — Legacy RS account: skip Step 2, use Step 1 tokens directly as `JX_ACCESS_TOKEN` / `JX_REFRESH_TOKEN`

---

## Architecture

### Files to Modify

| File | Change |
|------|--------|
| `JagexOAuth2Manager.kt` | Rewrite: 2 client IDs, 2 URL builders, fix game session API calls |
| `AuthRedirectCapture.kt` | Add `awaitConsentRedirect()` with forwarder HTML + 2-connection handling |
| `MainActivity.kt` | Wire `onNewIntent` to pass `jagex:` URI to ViewModel (partially done) |
| `SetupViewModel.kt` | Rewrite `startLogin()` to orchestrate full 2-step flow |
| `AndroidManifest.xml` | Already has `jagex:` intent filter (keep as-is) |

### Files Unchanged

- `CredentialManager.kt` — token storage unchanged
- `PkceHelper.kt` — PKCE generation unchanged (used for Step 1 only)

---

## Data Flow

```
User taps "Sign in with Jagex"
  |
  +-- STEP 1: Account Authentication
  |   +- Generate PKCE (S256, 64-byte verifier) + state nonce
  |   +- Build auth URL with com_jagex_auth_desktop_launcher
  |   +- Set awaitingStep1Auth = true
  |   +- Open Chrome Custom Tab
  |   |    -> User logs in at Jagex (Cloudflare passes - real Chrome)
  |   |    -> Jagex redirects to launcher-redirect?code=XXX&state=YYY
  |   |    -> Page triggers jagex:code=XXX&state=YYY&intent=social_auth
  |   |    -> Android launches our Activity via intent filter
  |   +- onNewIntent receives jagex: URI -> extract code + state
  |   +- Set awaitingStep1Auth = false
  |   +- Validate state matches stored nonce
  |   +- Exchange code for tokens (POST to token endpoint with PKCE verifier)
  |   +- Parse id_token JWT -> extract login_provider claim
  |   +- Branch:
  |        "jagex"     -> proceed to Step 2
  |        "runescape" -> skip to RuneScape flow (below)
  |
  +-- STEP 2: Consent (Jagex accounts only)
  |   +- Start LocalhostAuthServer on random port
  |   +- Build consent URL with 1fddee4e-... + nonce (48 chars)
  |   +- Open Chrome Custom Tab
  |   |    -> Jagex shows consent screen (user already logged in from Step 1)
  |   |    -> Redirects to http://localhost:<port>#id_token=XXX&code=YYY
  |   +- LocalhostAuthServer handles 2 requests:
  |   |    1. GET / -> serves forwarder HTML
  |   |    2. POST /jws -> receives id_token + code from JS
  |   +- Extract id_token from POST body (code is discarded)
  |   +- Stop server
  |
  +-- STEP 3: Game Session (API calls, no browser)
  |   +- POST {"idToken": "<jwt>"} to /game-session/v1/sessions
  |   |    -> Returns sessionId
  |   +- GET /game-session/v1/accounts with Bearer <sessionId>
  |   |    -> Returns [{accountId, displayName}, ...]
  |   +- Auto-select if single character
  |   +- Store: JX_SESSION_ID, JX_CHARACTER_ID, JX_DISPLAY_NAME
  |
  +-- DONE: Show Launch screen

RuneScape (legacy) flow (if login_provider == "runescape"):
  Step 1 tokens -> JX_ACCESS_TOKEN, JX_REFRESH_TOKEN
  GET /rs-profile/v1 with Bearer <accessToken> -> JX_DISPLAY_NAME
  -> Show Launch screen
```

---

## LocalhostAuthServer Changes

### Current behavior
- `awaitRedirect()`: accepts 1 connection, parses GET `/?code=XXX&state=YYY` query params
- Used for: previous single-step flow (now used only if needed for fallback)

### New behavior for Step 2
- `awaitConsentRedirect(state, timeoutMs)`: accepts 2 sequential connections
  1. **GET /**: Serves forwarder HTML
  2. **POST /jws**: Receives `id_token`, `code`, `state` from forwarder JS

### Forwarder HTML

```html
<html><body>
<h2>Completing login...</h2>
<script>
function onLoad() {
  if (window.location.hash.length > 1) {
    var params = new URLSearchParams(window.location.hash.slice(1));
    var body = new URLSearchParams();
    params.forEach(function(v, k) { body.append(k, v); });
    fetch('/jws', { method: 'POST', body: body,
      headers: {'Content-Type': 'application/x-www-form-urlencoded'}
    }).then(function() {
      document.body.innerHTML = '<h2>Login successful!</h2><p>Returning to app...</p>';
      setTimeout(function() {
        window.location = 'intent:#Intent;package=com.runelitetablet;end';
      }, 500);
    });
  } else {
    document.body.innerHTML = '<h2>Login failed</h2><p>No authentication data received.</p>';
  }
}
window.onload = onLoad;
</script>
</body></html>
```

### ConsentResult type

```kotlin
sealed class ConsentResult {
    data class Success(val idToken: String, val state: String) : ConsentResult() {
        override fun toString() = "ConsentSuccess(idToken=***, state=***)"
    }
    data class Error(val error: String, val description: String) : ConsentResult()
    object Cancelled : ConsentResult()
}
```

---

## Error Handling

| Scenario | Handling |
|----------|----------|
| User dismisses Custom Tab during Step 1 | `onResume()` detects `awaitingStep1Auth == true` + no jagex: intent -> "Login cancelled" with retry |
| jagex: redirect timeout (120s) | CompletableDeferred timeout -> "Login timed out - try again" |
| State mismatch (CSRF) | Reject, show "Security check failed - try again" |
| Token exchange HTTP error | Show sanitized error with retry |
| id_token missing login_provider | Default to "jagex" (do Step 2) |
| User dismisses Custom Tab during Step 2 | onResume detects server waiting, grace period then cancel |
| Forwarder JS fails (empty fragment) | Server timeout -> "Consent step failed - try again" |
| Game session API rejects id_token | Show error, offer retry from Step 1 |
| No Chrome / Custom Tab provider | Fall back to ACTION_VIEW (system browser) |
| Another app handles jagex: scheme | Android disambiguation dialog - user picks our app |
| Battery optimization kills app | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + singleTask re-create |

---

## Security

All existing measures preserved:
- **PKCE (S256)**: Step 1 only (Step 2 uses implicit/hybrid, no PKCE)
- **State/nonce**: CSRF protection on both steps
- **Loopback-only ServerSocket**: 127.0.0.1 binding
- **Encrypted storage**: EncryptedSharedPreferences
- **Credential sanitization**: Tokens redacted from logs
- **Timeout**: 120s on all async waits
- **id_token toString redaction**: Already implemented

---

## On-Device Test Results (Session 29)

| Test | Result |
|------|--------|
| Consent client with `response_type=id_token code` | `unsupported_response_type` error |
| Consent client with `response_type=code` | Same error |
| `jagex:` scheme via adb intent | Activity received full URI |
| `jagex:` scheme via Chrome `window.location` | Activity received full URI |

---

## Implementation Checklist

1. [ ] Rewrite `JagexOAuth2Manager.kt`: 2 client IDs, fix game session API (POST id_token, Bearer sessionId)
2. [ ] Add `awaitConsentRedirect()` to `AuthRedirectCapture.kt`: forwarder HTML + 2-connection handling
3. [ ] Wire `MainActivity.onNewIntent` to ViewModel (clean up test code)
4. [ ] Rewrite `SetupViewModel.startLogin()`: orchestrate Step 1 -> Step 2 -> Step 3
5. [ ] Keep `jagex:` intent filter in `AndroidManifest.xml`
6. [ ] Test on device: full login flow end-to-end
7. [ ] Clean up test artifacts (screenshots, HTML files, HTTP server)

## Not In Scope

- Token refresh (JX_SESSION_ID doesn't expire; re-auth for expired sessions)
- Multi-character picker UI (auto-select single character, defer multi)
- RuneScape profile API (`/rs-profile/v1`) — implement when a legacy account is available to test

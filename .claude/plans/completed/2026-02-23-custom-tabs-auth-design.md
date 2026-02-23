# Design: Replace WebView Auth with Chrome Custom Tabs

**Date**: 2026-02-23
**Status**: APPROVED
**Session**: 27

---

## Problem

Android WebView is fundamentally incompatible with Cloudflare's bot detection. The block persists indefinitely (not just 9 hours) because Cloudflare uses multiple detection layers that WebView cannot pass:

1. **TLS fingerprint mismatch**: WebView sends a different TLS ClientHello than Chrome (no extension permutation). Cloudflare's `mitmengine` compares TLS fingerprint against User-Agent — mismatch = spoofing detection.
2. **X-Requested-With header**: WebView sends `X-Requested-With: com.runelitetablet` on every request. Cloudflare Turnstile **always fails** when this header is present.
3. **Canvas/Picasso fingerprinting**: WebView renders differently than Chrome — Cloudflare detects the mismatch.
4. **Missing JS APIs**: WebView lacks `navigator.plugins`, `window.chrome`, `ServiceWorker`, `Notification` — all probed by Cloudflare's JS challenges.
5. **UA stripping made it worse**: Removing `; wv` from the User-Agent created a UA/TLS inconsistency — Cloudflare flags this with higher confidence than an honest WebView UA.

**All working third-party Jagex launchers** use real browsers:
- **Bolt**: Full CEF (Chromium Embedded Framework)
- **melxin/aitoiaita**: System browser via `xdg-open` + localhost server
- **rislah**: System browser + manual code paste

---

## Solution

Replace `AuthWebViewActivity` (embedded WebView) with **Chrome Custom Tabs + localhost redirect server** for all OAuth steps.

Chrome Custom Tabs run in the actual Chrome process — same TLS fingerprint, same canvas rendering, same JS APIs, same cookies. Cloudflare sees a real Chrome browser.

### Approach: Single Client ID + Localhost Redirect

Use the second client_id (`1fddee4e-b100-4f4e-b2b0-097f9088f9d2`) which accepts `http://localhost` as redirect_uri, for **both** OAuth steps. This eliminates the need to intercept `secure.runescape.com/m=weblogin/launcher-redirect` (which is impossible in Custom Tabs).

---

## Architecture

### Files Changed

| File | Change |
|------|--------|
| `AuthWebViewActivity.kt` | **DELETE** — no longer needed |
| `SetupViewModel.kt` | Rewrite `startLogin()` to use Custom Tab + localhost |
| `JagexOAuth2Manager.kt` | Unify both steps to use second client_id + localhost redirect |
| `AuthRedirectCapture.kt` | Enhance: better HTML response with auto-return to app |
| `AndroidManifest.xml` | Remove `AuthWebViewActivity` declaration |

### Files Unchanged

- `CredentialManager.kt` — token storage unchanged
- `PkceHelper.kt` — PKCE generation unchanged
- `LocalhostAuthServer` (in AuthRedirectCapture.kt) — already works, minor enhancements only

---

## Data Flow

```
User taps "Sign in with Jagex"
  │
  ├─ 1. Start LocalhostAuthServer on random port (e.g. 48372)
  │
  ├─ 2. Build auth URL:
  │     client_id = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2"
  │     redirect_uri = "http://localhost:48372"
  │     scopes = "openid offline gamesso.token.create user.profile.read"
  │     + PKCE challenge (S256) + state nonce
  │
  ├─ 3. Open Chrome Custom Tab to auth URL
  │     → User sees Jagex login page IN CHROME (passes Cloudflare)
  │     → User authenticates (username/password, 2FA, etc.)
  │
  ├─ 4. Jagex redirects to http://localhost:48372?code=XXX&state=YYY
  │     → LocalhostAuthServer.accept() captures the HTTP request
  │     → Extracts code and state from query parameters
  │     → Serves HTML: "Login successful! Returning to app..."
  │     → HTML includes: am start intent OR auto-close script
  │
  ├─ 5. Exchange code for tokens
  │     POST to https://account.jagex.com/oauth2/token
  │     client_id = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2"
  │     + PKCE verifier + redirect_uri
  │
  ├─ 6. Fetch characters: GET /game-session/v1/accounts
  │     ├─ Success → select character → createGameSession → done
  │     └─ 401/403 → second auth step (same Custom Tab + localhost pattern)
  │
  └─ 7. Store credentials (EncryptedSharedPreferences), show Launch screen
```

### Second Auth Step (if needed)

If `fetchCharacters()` returns 401/403 with the first token:

```
  ├─ Start new LocalhostAuthServer on new random port
  ├─ Build second auth URL with id_token_hint from first response
  │   client_id = same ("1fddee4e-b100-4f4e-b2b0-097f9088f9d2")
  │   redirect_uri = "http://localhost:<new_port>"
  │   scopes = "openid offline"
  │   response_type = "code"
  ├─ Open Chrome Custom Tab
  ├─ Capture redirect on localhost
  ├─ Exchange second code for tokens
  └─ Retry fetchCharacters with new access token
```

---

## Detecting Custom Tab Dismissal

Chrome Custom Tabs don't provide a direct callback for dismissal. Detection via Activity lifecycle:

1. `startLogin()` launches Custom Tab + starts localhost server awaiting redirect
2. Our Activity goes to `onPause()` (Custom Tab is in foreground)
3. When Custom Tab is dismissed (auth complete OR user closes), `onResume()` fires
4. In `onResume()`, check `LocalhostAuthServer` state:
   - **Got code**: proceed with token exchange (normal flow)
   - **Still waiting, no code**: user cancelled — stop server, show Login screen

Implementation: Use a `CompletableDeferred<AuthCodeResult>` in the server. On `onResume()`, check if it's completed. If not, wait a short grace period (500ms) then cancel.

---

## Error Handling

| Scenario | Handling |
|----------|----------|
| No Chrome/Custom Tab provider | Fall back to system browser Intent (ACTION_VIEW). Still passes Cloudflare. |
| User dismisses Custom Tab | `onResume()` detects no code → show "Login cancelled" with retry |
| Localhost server timeout (120s) | Cancel, show "Login timed out — try again" |
| Jagex rejects scopes for second client_id | Retry with reduced scopes (`openid offline`), then do second step for game tokens |
| Token exchange HTTP error | Show error message with retry button |
| Port conflict | `ServerSocket(0)` picks random port — extremely unlikely to conflict |
| State mismatch (CSRF) | Reject auth, show "Security check failed — try again" |

### Scope Fallback Strategy

If the second client_id doesn't support `gamesso.token.create` or `user.profile.read`:

1. First attempt: all 4 scopes → `openid offline gamesso.token.create user.profile.read`
2. If Jagex returns `invalid_scope` error: retry with `openid offline` only
3. After getting base tokens, do second auth step with game-specific scopes
4. Cache which scope set works to avoid retry on subsequent logins

---

## HTML Response Enhancement

The localhost server's HTML response should help the user return to the app:

```html
<html>
<head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="text-align:center; font-family:sans-serif; padding:40px;">
  <h2>Login successful!</h2>
  <p>Returning to RuneLite for Tablet...</p>
  <p><a href="intent:#Intent;package=com.runelitetablet;end">
    Tap here if not redirected automatically
  </a></p>
  <script>
    // Try to return to app via intent
    window.location = "intent:#Intent;package=com.runelitetablet;end";
  </script>
</body>
</html>
```

---

## Security Considerations

All existing security measures are preserved:

- **PKCE (S256)**: Prevents authorization code interception
- **State parameter**: CSRF protection — validated before token exchange
- **Loopback-only ServerSocket**: Binds to 127.0.0.1, not 0.0.0.0
- **Encrypted storage**: EncryptedSharedPreferences with AES-256-GCM
- **Access token memory-only**: Not persisted to disk
- **Credential sanitization**: Tokens redacted from all logs
- **Timeout protection**: 120-second timeout on localhost server

**New security benefit**: Chrome Custom Tabs share Chrome's cookie jar, meaning `cf_clearance` cookies persist across sessions — fewer Cloudflare challenges on repeat logins.

---

## Implementation Checklist

1. Delete `AuthWebViewActivity.kt`
2. Remove `AuthWebViewActivity` from `AndroidManifest.xml`
3. Update `JagexOAuth2Manager.kt`:
   - Unify Step 1 to use second client_id + localhost redirect
   - Add scope fallback logic
   - Update `exchangeCodeForTokens` to use second client_id
4. Update `SetupViewModel.kt`:
   - Rewrite `startLogin()`: start localhost server → open Custom Tab → await redirect
   - Add `onResume()` detection for Custom Tab dismissal
   - Remove all WebView-related imports and code
5. Enhance `AuthRedirectCapture.kt` (LocalhostAuthServer):
   - Improve HTML response with auto-return intent
   - Add success/failure page styling
6. Test on device:
   - First login (no cached Cloudflare cookies)
   - Repeat login (with cf_clearance cookie)
   - Cancel mid-flow
   - Timeout scenario
   - Second auth step if 401/403

# Jagex OAuth2: Correct Two-Step, Two-Client-ID Flow

## Status: VERIFIED 2026-02-23 (Session 28)

## Summary

Jagex OAuth2 is a **two-step flow using two different client IDs**. Our initial implementation incorrectly used the Step 2 client_id for Step 1, causing Jagex to return "Sorry, something went wrong." This document captures the correct flow as verified from three independent open-source implementations.

---

## The Two Steps

### Step 1: Account Authentication (Launcher Client)

| Parameter | Value |
|-----------|-------|
| **Client ID** | `com_jagex_auth_desktop_launcher` |
| **Redirect URI** | `https://secure.runescape.com/m=weblogin/launcher-redirect` |
| **Scopes** | `openid offline gamesso.token.create user.profile.read` |
| **Response Type** | `code` (Authorization Code Grant) |
| **Auth URL** | `https://account.jagex.com/oauth2/auth` |
| **Token URL** | `https://account.jagex.com/oauth2/token` |
| **PKCE** | S256, 64-byte random verifier |
| **Returns** | `access_token`, `refresh_token`, `id_token` (JWT with `login_provider` claim) |

### Step 2: Consent / Game Session (Consent Client)

| Parameter | Value |
|-----------|-------|
| **Client ID** | `1fddee4e-b100-4f4e-b2b0-097f9088f9d2` |
| **Redirect URI** | `http://localhost` |
| **Scopes** | `openid offline` |
| **Response Type** | `id_token code` (Hybrid/Implicit flow) |
| **Auth URL** | `https://account.jagex.com/oauth2/auth` |
| **Extra Params** | `nonce` (48 random alphanumeric chars), `prompt=consent` (Bolt) |
| **Returns** | `id_token` + `code` in URL fragment (`#id_token=...&code=...`) |

### Step 3: Game Session Creation (API calls, no browser)

| Endpoint | Purpose |
|----------|---------|
| `https://auth.jagex.com/game-session/v1/accounts` | Fetch character list (needs Step 2 id_token) |
| `https://auth.jagex.com/game-session/v1/sessions` | Create game session → returns `session_id` |
| `https://secure.jagex.com/rs-profile/v1` | RS profile info (for RuneScape-type accounts) |

---

## How Desktop Launchers Capture the Step 1 Redirect

The critical challenge: Step 1's redirect goes to `https://secure.runescape.com/m=weblogin/launcher-redirect`, NOT to localhost. Desktop launchers handle this in different ways:

### aitoiaita & melxin (Rust launchers)

The `launcher-redirect` page at `secure.runescape.com` triggers a **`jagex:` custom protocol** redirect:
```
jagex:code=XXXXX&state=YYYYY&intent=social_auth
```

1. Launcher registers as the OS handler for the `jagex:` protocol scheme
2. User's browser navigates to Jagex auth → user logs in
3. Jagex redirects to `launcher-redirect?code=XXX&state=YYY`
4. The redirect page triggers `jagex:code=...&state=...&intent=...`
5. OS invokes the launcher binary with the `jagex:` URI as argument
6. Launcher strips `jagex:` prefix, POSTs code/state to its local daemon on `/authcode`

**Source**: `src/daemon/launcher_client.rs` in [aitoiaita/linux-jagex-launcher](https://github.com/aitoiaita/linux-jagex-launcher)

### Bolt (C++/CEF)

Bolt embeds Chromium via CEF and intercepts navigation events directly:

1. CEF browser navigates to Jagex auth → user logs in
2. When CEF detects navigation to `secure.runescape.com/m=weblogin/launcher-redirect`
3. C++ code extracts `code` and `state` from query parameters
4. Redirects internally to `bolt-internal/index.html?code=XXX&state=YYY`
5. TypeScript picks up code/state and exchanges for tokens

**Source**: `src/browser/window_launcher.cxx` in [Adamcake/Bolt](https://codeberg.org/Adamcake/Bolt)

Config values are base64-encoded in C++ source:
- `Y29tX2phZ2V4X2F1dGhfZGVza3RvcF9sYXVuY2hlcg` → `com_jagex_auth_desktop_launcher`
- `aHR0cHM6Ly9zZWN1cmUucnVuZXNjYXBlLmNvbS9tPXdlYmxvZ2luL2xhdW5jaGVyLXJlZGlyZWN0` → `https://secure.runescape.com/m=weblogin/launcher-redirect`

---

## How Step 2 Redirect is Captured

Step 2 redirects to `http://localhost` which is straightforward:

### aitoiaita/melxin
- Daemon runs HTTP server on localhost port 80
- Consent redirect loads `http://localhost` → daemon serves `forwarder.html`
- The HTML page extracts `#id_token=...&code=...` from the URL fragment
- JavaScript POSTs the fragment data to the daemon's `/jws` endpoint
- Daemon uses `id_token` to call game session API → character list

### Bolt
- CEF intercepts navigation to `localhost/`
- Extracts fragment data containing id_token + code
- Redirects to internal handler

---

## What Our App Did Wrong (Session 28 Discovery)

### The Bug

Our `JagexOAuth2Manager.kt` used `1fddee4e-b100-4f4e-b2b0-097f9088f9d2` (the Step 2 consent client_id) for Step 1 (the initial browser login). This client_id is NOT registered for the full `gamesso.token.create user.profile.read` scopes and is NOT intended for the initial account authentication.

Jagex's OAuth server displayed "Sorry, something went wrong. Try again later." — a generic server-side rejection.

### The Fix Required

Must implement the correct two-step flow:
1. Step 1 uses `com_jagex_auth_desktop_launcher` + `launcher-redirect` redirect
2. Capture the redirect via `jagex:` intent scheme registered in AndroidManifest.xml
3. Step 2 uses `1fddee4e-...` + `http://localhost` redirect with implicit flow
4. Capture via localhost ServerSocket (existing `LocalhostAuthServer`)

### Android-Specific Approach: `jagex:` Intent Scheme

On Android, we can register as the handler for the `jagex:` URI scheme via an Activity intent filter:

```xml
<activity android:name=".auth.JagexSchemeActivity"
          android:exported="true"
          android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="jagex" />
    </intent-filter>
</activity>
```

When Chrome navigates to `jagex:code=...&state=...`, Android launches our activity with the full URI as the intent data. We extract code/state and proceed with token exchange.

**Alternative**: The `launcher-redirect` page may also POST to `http://localhost/authcode` (aitoiaita's daemon handles both). If so, a localhost server could capture Step 1 too — needs testing.

---

## Login Provider Types

The Step 1 `id_token` contains a `login_provider` claim:
- `"jagex"` → Jagex account (requires Step 2 consent + game session creation)
- `"runescape"` → Legacy RuneScape account (skip Step 2, use access_token + refresh_token directly with `JX_ACCESS_TOKEN` + `JX_REFRESH_TOKEN` env vars)

---

## Environment Variables for RuneLite Launch

### Jagex Account
```
JX_SESSION_ID=<from game session API>
JX_CHARACTER_ID=<selected character accountId>
JX_DISPLAY_NAME=<character display name>
```

### RuneScape Account (legacy)
```
JX_ACCESS_TOKEN=<from Step 1 token exchange>
JX_REFRESH_TOKEN=<from Step 1 token exchange>
JX_DISPLAY_NAME=<from RS profile API>
```

---

## Source Repos Analyzed

| Repo | Language | Step 1 Capture Method |
|------|----------|----------------------|
| [aitoiaita/linux-jagex-launcher](https://github.com/aitoiaita/linux-jagex-launcher) | Rust | `jagex:` protocol + xdg-open |
| [melxin/native-linux-jagex-launcher](https://github.com/melxin/native-linux-jagex-launcher) | Rust | `jagex:` protocol + xdg-open |
| [Adamcake/Bolt](https://codeberg.org/Adamcake/Bolt) | C++/TS | CEF navigation intercept |
| [rislah/jagex-launcher](https://github.com/rislah/jagex-launcher) | Go | (repo deleted/private) |

### Key Files Examined (via `gh api`)
- `aitoiaita: src/daemon/launcher_client.rs` — Step 1 constants + auth URL builder
- `aitoiaita: src/daemon/consent_client.rs` — Step 2 constants + consent URL builder
- `aitoiaita: src/daemon.rs` — Full HTTP daemon with /authcode, /jws, /launch endpoints
- `aitoiaita: src/forwarder.html` — Browser-side JS for Step 2 fragment capture
- `aitoiaita: src/daemon/jagex_oauth.rs` — JWT/id_token parsing
- `aitoiaita: src/daemon/game_session.rs` — Game session API calls

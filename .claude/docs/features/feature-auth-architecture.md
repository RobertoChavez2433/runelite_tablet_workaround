# Auth Feature Architecture

## Component Design

```
JagexOAuth2Manager
  ├── authenticateStep1() → OkHttp POST to launcher OAuth endpoint
  ├── authenticateStep2() → Launch GeckoAuthActivity for browser consent
  ├── createGameSession() → POST id_token to /sessions, GET /accounts
  ├── refreshTokens() → Refresh Step 1 tokens only (Step 2+3 need browser)
  └── validateSession() → GET /accounts?sessionId=... (401 = expired)

CredentialManager
  ├── EncryptedSharedPreferences (AES256_GCM via Android Keystore)
  ├── save(credentials) → persist tokens
  ├── load() → retrieve tokens
  ├── clear() → wipe all credentials
  └── handleCorruption() → delete file + retry once

GeckoAuthActivity
  ├── GeckoView session (NOT Android WebView — Cloudflare blocks it)
  ├── NavigationDelegate → intercept jagex: URI redirect
  ├── Parse auth code from jagex: URI (commas, not &)
  └── Return result to calling Activity
```

## OAuth Flow Sequence

```
Step 1: App → POST /auth (com_jagex_auth_desktop_launcher) → launcher tokens
Step 2: App → GeckoAuthActivity → User consents → jagex: redirect → auth code
Step 3: App → POST /sessions (id_token) → sessionId
         App → GET /accounts?sessionId → character list + game tokens
```

## Credential Lifecycle

| Token | Storage | Lifetime | Refresh |
|-------|---------|----------|---------|
| launcher access_token | In-memory preferred | ~1 hour | Via refresh_token |
| launcher refresh_token | EncryptedSharedPreferences | Long-lived | Re-auth if revoked |
| JX_SESSION_ID | EncryptedSharedPreferences | ~12 days | Cannot refresh — needs full re-auth |
| JX_ACCESS_TOKEN | EncryptedSharedPreferences | Game session | Tied to JX_SESSION_ID |

## Key Design Decisions

- **GeckoView over WebView** — Cloudflare blocks Android WebView; port 80 is kernel-blocked. GeckoView intercepts navigation before network.
- **Temp env file for credentials** — Not CLI args (visible in `ps` output). Written to app-private storage, deleted immediately after `source`.
- **Session validation before launch** — JX_SESSION_ID expires server-side. Refresh tokens don't help because Step 2 consent requires a browser.
- **Corruption recovery** — EncryptedSharedPreferences file deleted and recreated on corruption to prevent permanent brick.

## Security Model

- All tokens in EncryptedSharedPreferences (AES256_GCM, Android Keystore)
- Tokens never logged — `toString()` returns `"[REDACTED]"`
- Env file written to `getFilesDir()` only
- Shell values escaped with `printf %q` equivalent

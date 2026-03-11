# Auth Agent Memory

## Jagex OAuth 2-Step Flow

### Step 1: Launcher Authentication
- Client ID: `com_jagex_auth_desktop_launcher`
- Endpoint: Jagex auth server
- Result: launcher access_token, refresh_token, id_token

### Step 2: Browser Consent (GeckoView)
- User authenticates in GeckoView browser
- Jagex redirects with `jagex:` URI scheme
- `jagex:` URI uses COMMAS as parameter separators (not `&`)
- Extract auth code from redirect URI

### Step 3: Game Session Creation
- POST id_token to `/sessions` — returns sessionId
- GET `/accounts?sessionId=...` — returns character list + game tokens
- ORDER MATTERS: POST first, GET second

## Token Lifecycle

| Token | Storage | Lifetime | Can Refresh? |
|-------|---------|----------|-------------|
| launcher access_token | In-memory | ~1 hour | Yes (refresh_token) |
| launcher refresh_token | Encrypted prefs | Long-lived | Re-auth if revoked |
| JX_SESSION_ID | Encrypted prefs | ~12 days | NO — needs full re-auth via browser |
| JX_ACCESS_TOKEN | Encrypted prefs | Tied to session | NO — tied to JX_SESSION_ID |
| JX_REFRESH_TOKEN | Encrypted prefs | Tied to session | NO — tied to JX_SESSION_ID |

## Critical Facts

- JX_SESSION_ID DOES expire (~12 days inactivity verified via API tests, Session 41)
- refreshTokens() only renews Step 1 — CANNOT recreate game session without browser
- "Successful" refresh does NOT mean valid game session
- WebView permanently Cloudflare-blocked — MUST use GeckoView
- Port 80 kernel-blocked on Android — GeckoView intercepts before network

## Credential Security

- All tokens in EncryptedSharedPreferences (AES256_GCM, Android Keystore)
- Tokens delivered to shell via temp env file (NOT CLI args)
- Env file in getFilesDir(), deleted after source
- Shell values escaped with printf %q or equivalent
- Token toString() returns "[REDACTED]"

## Known Issues

- Auth refresh Step 1/3 only — P0 blocker, fix planned
- GeckoAuthActivity process death not handled — needs savedInstanceState check
- EncryptedSharedPreferences corruption not handled — needs delete+retry
- shellEscape metacharacter coverage incomplete — needs all 5 chars + newlines

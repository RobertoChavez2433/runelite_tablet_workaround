# Auth Feature Overview

## Purpose

The Auth layer handles Jagex account authentication for Old School RuneScape. It implements the Jagex 3-step OAuth flow, manages credential storage, and provides secure token delivery to RuneLite's launch environment.

## Key Capabilities

- **Jagex 3-step OAuth** — Launcher auth, browser consent (GeckoView), game session creation
- **Credential storage** — EncryptedSharedPreferences backed by Android Keystore
- **Session validation** — Pre-launch check that game session is still valid
- **Secure token delivery** — Credentials passed to shell via temp env file (not CLI args)
- **Token refresh** — Automatic refresh of launcher tokens (Step 1 only)

## How It Works

1. **Step 1**: Authenticate with Jagex launcher client ID (`com_jagex_auth_desktop_launcher`)
2. **Step 2**: Open GeckoView browser for user consent — Jagex redirects with auth code via `jagex:` URI
3. **Step 3**: Exchange auth code for game session — POST id_token to `/sessions`, GET `/accounts` with sessionId
4. Store credentials in EncryptedSharedPreferences
5. Before each launch, validate session via GET `/accounts?sessionId=...`
6. Write env file with credential values, RuneLite sources it on launch

## Key Files

| File | Role |
|------|------|
| `JagexOAuth2Manager.kt` | OAuth flow orchestration, token refresh |
| `CredentialManager.kt` | EncryptedSharedPreferences storage |
| `GeckoAuthActivity.kt` | GeckoView browser for consent flow |

## Related

- Constraints: `architecture-decisions/auth-constraints.md`
- Rules: `rules/auth.md`
- Defects: `defects/_defects-auth.md`
- Research: `research/jagex-oauth2-two-step-flow.md`

---
paths:
  - "**/auth/**/*.kt"
---

# Auth Rules

## Jagex OAuth 2-Step Flow

Jagex OAuth is 2 steps with 2 client IDs:
1. **Step 1**: Authenticate with `com_jagex_auth_desktop_launcher` client ID — gets launcher tokens
2. **Step 2**: Exchange launcher tokens for game session — POST `id_token` to `/sessions` FIRST, then GET `/accounts` with `sessionId` SECOND
3. The `jagex:` URI scheme uses commas (not `&`) as parameter separators

## Hard Rules

1. **WebView is permanently Cloudflare-blocked** — port 80 is kernel-blocked on Android; use GeckoView which intercepts navigation before network
2. **JX_SESSION_ID DOES expire** (~12 days inactivity) — refresh token stays valid longer but cannot recreate game session without browser (Step 2 consent)
3. **Refresh only renews Step 1 tokens** — `refreshTokens()` does NOT recreate the game session; a "successful" refresh still results in silent game login failure
4. **Pre-launch validation required** — before launching RuneLite, validate session via `GET /accounts?sessionId=...`; on 401, clear credentials and trigger full GeckoView re-auth flow
5. **Credential env vars passed to shell** — `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`, `JX_ACCESS_TOKEN`, `JX_REFRESH_TOKEN` must be properly shell-escaped

## GeckoView Rules

- GeckoAuthActivity must handle process death — check `savedInstanceState != null` in `onCreate` and finish with error
- Add `FLAG_SECURE` to prevent login page appearing in recents
- GeckoView sessions and `lateinit` vars cannot survive process death

## Credential Security

- All tokens stored in `EncryptedSharedPreferences` backed by Android Keystore — never plain `SharedPreferences`
- Tokens passed to launch script via temp file, NOT as command-line args (visible in `ps` output)
- EncryptedSharedPreferences corruption (power loss/disk corruption) must be handled: delete corrupted file and retry once with a guard flag
- All credential values written to env file must be properly shell-quoted using `printf %q` or equivalent

## Anti-Patterns

- Calling `refreshTokens()` and assuming the game session is valid
- Passing tokens as shell command arguments
- Using plain `SharedPreferences` for any auth data
- Skipping session validation before launch
- Not handling `EncryptedSharedPreferences` corruption

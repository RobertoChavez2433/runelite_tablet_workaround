# Auth Constraints

Per-package hard rules and soft guidelines derived from Solved Problems #2, #3 and auth defect patterns.

## Hard Rules (Reject Proposal If Violated)

1. **Jagex OAuth is 2 steps, 2 client IDs** — Step 1 uses `com_jagex_auth_desktop_launcher`, Step 2 uses `1fddee4e-...`. POST `id_token` to `/sessions` FIRST, then GET `/accounts` with `sessionId` SECOND. Reversing the order or skipping Step 2 means the game session is never created.

2. **`jagex:` URI uses commas, not `&`** — Parameter separators in the `jagex:` redirect URI scheme are commas. Standard URL parsing will fail.

3. **WebView is Cloudflare-blocked** — Port 80 is kernel-blocked on Android. GeckoView solves both by intercepting navigation before network. Never attempt to use Android WebView for Jagex authentication.

4. **JX_SESSION_ID expires server-side** (~12 days inactivity) — Refresh token stays valid longer but CANNOT recreate game session without browser (Step 2 consent flow). A "successful" token refresh does NOT mean the game session is valid.

5. **Pre-launch session validation required** — Before launching RuneLite, validate via `GET /accounts?sessionId=...`. On 401, clear credentials and trigger full GeckoView re-auth flow. Never assume a non-expired refresh token means a valid game session.

6. **EncryptedSharedPreferences corruption handling** — Power loss or disk corruption can break the encrypted prefs XML. On `GeneralSecurityException`, delete the corrupted file and retry once with a guard flag to prevent infinite loops.

## Soft Guidelines (Discuss Before Proceeding)

- Access tokens (short-lived, ~1hr) should be kept in-memory only, not persisted to disk
- GeckoAuthActivity must check `savedInstanceState != null` and finish with error on process death
- Add `FLAG_SECURE` to GeckoAuthActivity to prevent login page in recents
- Token data classes should override `toString()` to return `"[REDACTED]"`
- Shell credential values must be properly shell-quoted using `printf %q` or equivalent

## Defect Patterns

- `refreshTokens()` renews Step 1 launcher tokens but skips Step 2 and Step 3 — game login silently fails
- GeckoView sessions and `lateinit` vars cannot survive process death — `UninitializedPropertyAccessException`
- EncryptedSharedPreferences corruption bricks credential storage permanently if not handled

## Related Files

- `rules/auth.md` — path-triggered rule file
- `defects/_defects-auth.md` — active defect patterns

# Security Constraints

Cross-cutting hard rules and soft guidelines for credential handling, shell injection prevention, IPC security, and data leakage. Applies across all packages wherever credentials, tokens, or shell commands are involved.

## Hard Rules (Reject Proposal If Violated)

1. **shellEscape must cover ALL bash metacharacters including newlines** — Single-quote escaping (`'\''`) misses `$()`, backtick, and other expansions when the file is `source`d. Newlines in credential values break out of quoted strings entirely, enabling shell injection. Use double-quote escaping for all 5 metacharacters (`\`, `"`, `$`, `` ` ``, `!`) PLUS strip `\n`, `\r`, `\0`. Or use `printf %q` (bash-native).

2. **No credential values in command-line arguments** — Arguments appear in `ps` output and shell history. Pass credentials via temp env file written to `getFilesDir()`, sourced by the script, and deleted immediately after `source`. Android-side cleanup in a `finally` block or backup coroutine.

3. **EncryptedSharedPreferences corruption must be handled** — Power loss or disk corruption can break the encrypted prefs XML. On `GeneralSecurityException`, delete the corrupted file and retry once. Guard with a `prefsRecreateAttempted` flag to prevent infinite loops. Unhandled, this bricks credential storage permanently.

4. **PendingIntents sent to Termux must use FLAG_MUTABLE** — `FLAG_IMMUTABLE` prevents the intent runtime from writing result extras back into the PendingIntent. The result bundle silently vanishes. Any PendingIntent that carries return data must use `FLAG_MUTABLE`.

5. **No secrets in logs or crash reports** — No token values (`access_token`, `refresh_token`, `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`) in any `Log.*()` call. Token data classes must override `toString()` to return `"[REDACTED]"`. Verbose/debug logs must be gated on `BuildConfig.DEBUG`.

6. **FLAG_SECURE on auth screens** — `GeckoAuthActivity` must set `FLAG_SECURE` on its window to prevent the login page from appearing in the Android recents screenshot. Without it, the auth page is visible to any app that reads the recents stack.

## Soft Guidelines (Discuss Before Proceeding)

- `access_token` (short-lived, ~1hr) should be kept in-memory only, not persisted to disk
- `TermuxResultService` and `InstallResultReceiver` should be declared `exported="false"`
- Result handlers should validate execution IDs against a `ConcurrentHashMap`; discard unknown IDs immediately
- OkHttp logging interceptor should use `Level.NONE` in release builds; never log response bodies for token endpoints
- Downloaded APKs stored in `getCacheDir()` (app-private); deleted after PackageInstaller session opens
- Temp env file path constructed from timestamp only, never user-controlled data
- `GeckoAuthActivity` must check `savedInstanceState != null` in `onCreate` and finish with error on process death

## Defect Patterns

- `shellEscape` using single-quote pattern misses newlines and `$()` — shell injection vector when credentials are sourced
- `FLAG_IMMUTABLE` on Termux PendingIntent causes result bundle to silently vanish — command completes but result is never delivered
- `EncryptedSharedPreferences` corruption with no recovery path — `GeneralSecurityException` returns null forever, user must uninstall
- GeckoView sessions and `lateinit` vars cannot survive process death — `UninitializedPropertyAccessException` on restore

## Related Files

- `rules/auth.md` — path-triggered auth rule file
- `rules/termux-integration.md` — path-triggered Termux IPC rules
- `defects/_defects-security.md` — active security defect patterns

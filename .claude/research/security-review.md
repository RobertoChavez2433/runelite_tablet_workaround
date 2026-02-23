# Security Review: Runelite for Tablet

**Date**: 2026-02-23
**Scope**: Full codebase + PRD + plans review (Slice 1 + Slice 2/3 MVP)
**Threat model**: Personal use — device theft, ADB access, rogue app, token leakage

---

## Overall Posture

**Rating**: MODERATE — sound for personal use, hardening needed before distribution.

**Strengths:**
- EncryptedSharedPreferences with Keystore-backed AES256-GCM ✓
- Credentials masked in logs (`JX_SESSION_ID=***`) ✓
- Temp env file pattern for token → shell passthrough ✓
- `printf %q` quoting for shell variable assignment ✓
- `exported="false"` on TermuxResultService and InstallResultReceiver ✓
- Execution ID validation via ConcurrentHashMap ✓
- PKCE (RFC 7636) with 64-byte SecureRandom verifier ✓
- CSRF state nonce via SecureRandom ✓

**Gaps (Personal Use):**
- No `network_security_config.xml` — cleartext policy not explicit
- `access_token` may be persisted (should be in-memory only)
- Downloaded APK integrity not verified (no checksum or cert check)
- PendingIntents not cancelled after result received
- Stale credential env files not cleaned at app startup (crash recovery)
- APK cache location should be `getCacheDir()`, verify it is

**Gaps (Pre-Distribution, Not Blocking Now):**
- No TLS certificate pinning for Jagex OAuth2 endpoints
- No `Log.v/d` stripping via ProGuard in release builds
- Sequential execution IDs (use UUID for distribution)
- No rooted device detection/warning

---

## Risk Inventory

### P0 — Must Fix Before Any Use (None identified in current code, but verify these)

1. **Token value in any log statement** — check all `Log.*()` calls in auth/ package
2. **`SharedPreferences` (unencrypted) used for any token** — verify CredentialManager uses EncryptedSharedPreferences only
3. **Token passed as shell argument** — verify all credential passing goes through env file, not args

### P1 — Fix Before Sharing/Testing

4. **No `network_security_config.xml`** — add with `cleartextTrafficPermitted="false"` and no user CAs
5. **`access_token` persisted** — should be in-memory only; only `refresh_token` and `JX_SESSION_ID` need persistence
6. **PendingIntents not cancelled after use** — call `pendingIntent.cancel()` after result received in both TermuxCommandRunner and ApkInstaller
7. **Stale env file cleanup at startup** — delete any `launch_env_*.sh` files from `getFilesDir()` on app start
8. **APK download to wrong location** — must be `getCacheDir()`, not `getFilesDir()` or external

### P2 — Before Distribution

9. **APK signature verification** — use `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` to verify package name + signing cert before installing
10. **TLS certificate pinning** — pin `account.jagex.com` and `auth.jagex.com` via OkHttp `CertificatePinner`
11. **ProGuard strip debug logs** — `-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }`
12. **Sequential execution IDs** — replace AtomicInteger with `UUID.randomUUID().toString()` for distribution
13. **`toString()` override on token data classes** — return `"[REDACTED]"` to prevent accidental exposure

---

## Area Analysis

### Credential Storage
- EncryptedSharedPreferences (CredentialManager.kt) — correct implementation
- Android Keystore AES256-GCM — hardware-backed on Android 9+ (Tab S10 Ultra qualifies)
- Lazy init handles device-locked state — graceful null return
- **Verify**: `access_token` is NOT persisted (short-lived, should be memory-only)

### Token Transport (App → proot Shell)
- Temp file written to `getFilesDir()` as `launch_env_${timestamp}.sh`
- Shell scripts `source` file then delete immediately
- Android-side coroutine deletes after 5s
- **Verify**: backup cleanup at app startup for crash recovery
- `printf %q` quoting in `launch-runelite.sh` — correct mitigation for shell injection

### Network Security
- OkHttp with standard system TrustManager — correct for personal use
- **Missing**: `network_security_config.xml` explicitly blocking cleartext and user CAs
- `Authorization` header should be redacted in any HttpLoggingInterceptor
- Read timeout of 5 minutes (APK downloads) — acceptable; API calls should be ≤30s

### Intent Security
- TermuxResultService: `exported="false"` ✓ but Termux must still be able to fire the PendingIntent
- Execution ID tracked in ConcurrentHashMap; unknown IDs discarded ✓
- PendingIntent `FLAG_MUTABLE` required for Termux (fill-in extras) — correct, documented ✓
- **Missing**: `pendingIntent.cancel()` after result received

### Shell Script Injection
- `printf %q` in `launch-runelite.sh` ✓
- Env file path timestamp-based (not user-controlled) ✓
- Credentials never passed as command arguments ✓
- **Verify**: all NEW Slice 2/3 scripts follow same quoting pattern

### APK Download
- GitHub HTTPS ✓
- Stored in `cacheDir` — **verify this is the case in ApkDownloader.kt**
- ZIP magic byte verification for RuneLite.jar ✓
- **Missing**: APK package name + signing cert verification before install

### Logging
- Credential masking in known log statements ✓
- **Verify**: no token values in OkHttp response logging, exception messages, or data class toString()
- App log files in app-private storage ✓ (when implemented)

---

## Accepted Risks (Personal Use MVP)

| Risk | Why Accepted | Revisit Trigger |
|------|-------------|-----------------|
| No TLS pinning | System CAs sufficient; personal device | Distribution |
| No APK hash/cert check | GitHub HTTPS + open-source content | Distribution |
| Sequential execution IDs | Consumed immediately; narrow window | Distribution |
| proot not a kernel sandbox | Android file permissions still apply | N/A |
| RuneLite plugin ecosystem | Out of app scope | N/A |
| OpenJDK 11 EOL | MVP only; upgrade to JDK 17 in Phase 2 | Phase 2 |

---

## Recommended Actions (Ordered by Priority)

1. **Add `network_security_config.xml`** — 10-minute task, high value
2. **Verify `access_token` is not persisted** — check CredentialManager, only `refresh_token` + `JX_SESSION_ID` should be in EncryptedSharedPreferences
3. **Cancel PendingIntents after use** — add `pendingIntent.cancel()` to TermuxCommandRunner and ApkInstaller result handlers
4. **Startup cleanup for stale env files** — add `getFilesDir().listFiles { f -> f.name.startsWith("launch_env_") }?.forEach { it.delete() }` to app startup
5. **Verify APK download location** — confirm `cacheDir` used in ApkDownloader, not filesDir or external
6. **Add APK package name verification** — `PackageManager.getPackageArchiveInfo()` before install (5-minute add)
7. **Audit Slice 2/3 auth code logs** — grep for any token value in log statements in auth/ package

---

## Reference

- Agent definition: `.claude/agents/security-review-agent.md`
- Research conducted: 2026-02-23 via parallel scope review + best practices research agents
- Scope coverage: All 25 source files, PRD, implementation plans, research docs

# RuneLite for Tablet — Slice 2+3 Implementation Plan

**Date**: 2026-02-22
**Status**: Final (post adversarial review + OAuth2 research)
**Scope**: Slice 2 "Setup is Smooth" + Slice 3 "Auth Works" combined

**Confirmed decisions**:
- **Auth**: Native in-app OAuth2 (Jagex OAuth2 endpoints, Chrome Custom Tab, OkHttp for token exchange)
- **State persistence**: Marker files in Termux filesystem (ground truth) + Android SharedPreferences (UI cache)
- **Core UX principle**: Open app → steps run invisibly → Termux:X11 auto-opens with RuneLite ready. User never touches Termux.

---

## User Journey

**Target experience (returning user, all setup done, auth valid)**:
1. Open app → single "Launch RuneLite" button shown instantly
2. Tap Launch → Termux:X11 opens automatically
3. RuneLite appears in Termux:X11, already logged in, ready to play

**First install**:
1. Open app → setup wizard, all 7 steps pending
2. Tap "Start Setup" → steps run automatically in background (Termux windows stay hidden)
3. All 7 steps complete → login screen shown ("Sign in with Jagex")
4. Login completes → character selected → "Launch RuneLite" button
5. Tap Launch → Termux:X11 opens, RuneLite appears

**Subsequent opens (setup done, valid session)**:
1. Open app → SharedPreferences loaded instantly, `check-markers.sh` fires in background
2. All markers confirmed → skip wizard, show Launch screen directly
3. One tap to play

The user should never open Termux manually, see a terminal, or know proot exists.

---

## Slice 2: Setup Is Smooth

### What's Already Done (from Slice 1)

| Task | Status | Notes |
|---|---|---|
| 2.1 Break into discrete steps | **PARTIAL** | 7 steps defined, but steps 3–5 run as ONE monolithic script |
| 2.2 Setup Wizard UI | **DONE** | `SetupScreen.kt` + `StepItem.kt` — full stepper with icons, colors |
| 2.3 Step execution engine | **DONE** | `SetupOrchestrator.kt` — runs steps, captures output, detects success |
| 2.4 Retry + resume | **PARTIAL** | Retry exists but re-runs entire monolithic block; resume is in-memory only |
| 2.5 Persist setup state | **MISSING** | No SharedPreferences, no marker files |

### Architecture

#### Shell Scripts: Modular

**Current**: `setup-environment.sh` installs proot + Java + RuneLite in one execution. Steps 3–5 update status in batch.

**Target**:

```
assets/scripts/
├── install-proot.sh           # Step 3
├── install-java.sh            # Step 4 (also installs x11-xserver-utils for xrandr)
├── download-runelite.sh       # Step 5
├── check-markers.sh           # Startup utility: reports marker presence
├── launch-runelite.sh         # Step 7: launch (unchanged structure, credential injection added)
└── setup-environment.sh       # DEPRECATED — kept with comment, no longer called
```

**Mandatory shell script pattern for proot calls**:

proot emits `/proc/self/fd/0,1,2` binding warnings in background (no-PTY) mode, producing non-zero exits even on success. `set -euo pipefail` must never be applied blindly to proot calls.

```bash
# WRONG — proot non-zero exit kills script before marker is written
proot-distro login ubuntu -- apt-get install -y openjdk-11-jdk

# CORRECT — wrap proot, then verify positively, then write marker
proot-distro login ubuntu -- apt-get install -y openjdk-11-jdk < /dev/null 2>&1 || true
JAVA_BIN=$(proot-distro login ubuntu -- which java 2>/dev/null || echo "")
if [ -z "$JAVA_BIN" ]; then
    echo "ERROR: java binary not found after install" >&2
    exit 1
fi
mkdir -p "$MARKER_DIR" && touch "$MARKER_DIR/step-java.done"
```

**Script versioning**: Each script writes `SCRIPT_VERSION=2` to `~/.runelite-tablet/markers/version`. On startup, if stored version ≠ current app version → clear all markers, re-run full setup. This ensures APK updates with changed scripts don't silently skip re-required steps.

#### Persistent State: Two-Layer

**Marker files** — ground truth. Live in Termux home (`~/.runelite-tablet/markers/`). Survive APK reinstall. Written by shell scripts after positive verification.

**SharedPreferences** — UI cache. Written by `SetupStateStore` when a step completes. Read instantly on app start for immediate UI render. Reconciled against marker files on each startup.

**`check-markers.sh` output protocol** — structured to distinguish "absent" from "script failed":

```bash
echo "PRESENT step-proot"   # marker file exists
echo "ABSENT step-java"     # marker file missing
echo "VERSION 2"            # current version in markers dir
```

Kotlin reconciliation rules:
- `result.isSuccess == false` → inconclusive; keep SharedPreferences as-is (Termux down, timeout, etc.)
- `result.isSuccess == true` → parse lines; ABSENT downgrades step to Pending, PRESENT upgrades to Completed

#### SetupOrchestrator Refactor

**Must delete**: `setupScriptRan: Boolean` field and `runSetupScript()` function. These must be removed entirely, not refactored. If they remain, steps 3–5 silently route to the old monolithic script.

Replace with:
```kotlin
InstallProot     -> executeModularScript("install-proot.sh",     "step-proot")
InstallJava      -> executeModularScript("install-java.sh",      "step-java")
DownloadRuneLite -> executeModularScript("download-runelite.sh", "step-runelite")
```

`evaluateCompletedSteps()` must become a `suspend` function. Call it at the top of `runSetup()` (already suspend), not from `init {}`. This avoids blocking the main thread and prevents the race condition where steps begin before reconciliation completes.

### Tasks

#### 2.1 — Three Modular Shell Scripts

**`install-proot.sh`** (NEW):
- Install `proot-distro` via `apt-get` (`|| true` on non-critical errors)
- Install Ubuntu rootfs using manual extraction fallback (existing pattern from `setup-environment.sh` — proot-distro exits non-zero due to `/proc/self/fd` warnings)
- Configure DNS (`resolv.conf`), locale, `DEBIAN_FRONTEND=noninteractive`
- Positive verification: check rootfs directory exists at `$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/`
- Write `step-proot.done` marker and `version` file only after verification
- Idempotent: skip extraction if rootfs directory exists

**`install-java.sh`** (NEW):
- Run `proot-distro login ubuntu -- apt-get install -y openjdk-11-jdk x11-xserver-utils` (includes xrandr)
- Wrap with `|| true`; verify with `proot-distro login ubuntu -- which java` (NOT `dpkg -l` — unreliable for partially installed packages)
- Write `step-java.done` marker only after `which java` returns a path

**`download-runelite.sh`** (NEW):
- Fetch latest JAR URL: `https://api.github.com/repos/runelite/launcher/releases/latest`
- Hardcoded fallback version constant (must be updated per RuneLite release — document in code comment)
- Download `RuneLite.jar` to proot home
- Verify: file size > 1MB via `wc -c`; check ZIP magic bytes `50 4B 03 04` via `od -A n -t x1 -N 4`
- Write `step-runelite.done` marker only after verification

**`check-markers.sh`** (NEW):
- Print `PRESENT step-proot` / `ABSENT step-proot` for each tracked step
- Print `VERSION <n>` from version file (or `VERSION none` if missing)
- Exit 0 always (only non-zero if the script itself crashes)

#### 2.2 — `SetupStateStore.kt` (NEW)

```kotlin
class SetupStateStore(private val context: Context) {
    companion object {
        const val CURRENT_SCRIPT_VERSION = "2"
    }

    private val prefs = context.getSharedPreferences("setup_state", Context.MODE_PRIVATE)

    fun markCompleted(key: String) = prefs.edit().putBoolean(key, true).apply()
    fun isCompleted(key: String): Boolean = prefs.getBoolean(key, false)
    fun getStoredVersion(): String = prefs.getString("script_version", "0") ?: "0"
    fun setStoredVersion(v: String) = prefs.edit().putString("script_version", v).apply()
    fun isVersionCurrent() = getStoredVersion() == CURRENT_SCRIPT_VERSION
    fun clearAll() = prefs.edit().clear().apply()
}
```

Step keys: `"step-proot"`, `"step-java"`, `"step-runelite"`. Termux/X11 install steps use package-presence checks (existing pattern). `EnablePermissions` uses the Termux echo test (existing pattern).

#### 2.3 — Refactor `SetupOrchestrator`

1. Inject `SetupStateStore` via constructor
2. Delete `setupScriptRan` field and `runSetupScript()` function
3. Add `suspend fun executeModularScript(name: String, markerKey: String)`:
   - Runs script via `commandRunner.execute()`
   - On success: `stateStore.markCompleted(markerKey)`
4. Update `executeStep()` when-branch for `InstallProot`, `InstallJava`, `DownloadRuneLite` to call `executeModularScript()`
5. Make `evaluateCompletedSteps()` suspend
6. At top of `runSetup()`: check version → if stale, `stateStore.clearAll()` → call `evaluateCompletedSteps()`

#### 2.4 — Fix Retry (Per-Step)

With modular scripts, retry is naturally per-step — `retryCurrentStep()` re-runs only the failed step's script. On successful retry: call `stateStore.markCompleted(markerKey)`.

No structural changes to retry logic needed beyond the modular script wiring.

#### 2.5 — Fix Resume on App Restart

Startup flow inside `runSetup()`, before `runSetupFrom()`:

1. Check `stateStore.isVersionCurrent()`. If false → `stateStore.clearAll()`, run from step 1
2. Load completed steps from `stateStore` → mark as `Completed` in `_steps` StateFlow
3. Set UI to `Reconciling` state ("Verifying setup…" shown, buttons disabled)
4. Fire `check-markers.sh` (suspend, 10-second timeout)
5. On success: parse PRESENT/ABSENT lines → reconcile `_steps` and `stateStore`
6. On failure (timeout, Termux not running): keep SharedPreferences state, log warning, proceed
7. On version mismatch from markers: `stateStore.clearAll()`, re-run from step 1
8. Clear `Reconciling` state, enable UI

Add `Reconciling` to `SetupState` sealed class to prevent step-flicker during startup.

#### 2.6 — Fix UX Defects

**X11 display size (Tab S10 Ultra 2960×1848)**

`install-java.sh` already installs `x11-xserver-utils` (includes xrandr). In `launch-runelite.sh`, after X11 socket confirmed:

```bash
# Calculate modeline at implementation time: cvt 2960 1848 60
# Then:
DISPLAY=:0 xrandr --newmode "2960x1848" <modeline_values> 2>/dev/null || true
DISPLAY=:0 xrandr --addmode default "2960x1848" 2>/dev/null || true
DISPLAY=:0 xrandr --output default --mode "2960x1848" 2>/dev/null || true
```

For HiDPI Java Swing scaling (NOT `--scale`, which doesn't exist in RuneLite CLI):
```bash
exec java -Dsun.java2d.uiScale=2.0 -cp "$CLASSPATH" net.runelite.client.RuneLite "$@"
```

**Seamless Termux:X11 auto-launch**

Launch sequence (order matters):
1. **Check Termux:X11 installed** — before anything else:
   ```kotlin
   if (packageManager.getLaunchIntentForPackage(TermuxPackageHelper.TERMUX_X11_PACKAGE) == null) {
       // Show error: "Termux:X11 not installed — run Setup first"
       return
   }
   ```
2. **Bring Termux:X11 to foreground** — via `SetupActions.launchIntent()` (routes through Activity, not background context — required on Android 10+ for background Activity starts):
   ```kotlin
   actions?.launchIntent(packageManager.getLaunchIntentForPackage(TERMUX_X11_PACKAGE)!!)
   ```
3. **Poll for X11 socket** — in Termux's filesystem at `$PREFIX/tmp/.X11-unix/X0` (NOT inside proot — the socket is created by Termux:X11 and bind-mounted into proot by `launch-runelite.sh`). Poll via `check-x11-socket.sh` (simple one-liner: `[ -S "$PREFIX/tmp/.X11-unix/X0" ] && echo "READY" || echo "WAITING"`). Retry every 500ms for up to 30 seconds.
4. **Fire `launch-runelite.sh`** once socket confirmed

Add `TERMUX_X11_PACKAGE = "com.termux.x11"` constant to `TermuxPackageHelper`. Verify this matches the package name installed by the `InstallTermuxX11` step.

### Exit Criteria

- [ ] Setup completes on fresh install, all 7 steps pass
- [ ] Close app after step 3 completes, reopen → resumes from step 4 (not step 1)
- [ ] Force-fail step 4 (Java), tap Retry → only Java step re-runs
- [ ] Reinstall APK with bumped `CURRENT_SCRIPT_VERSION` → setup re-runs from step 1
- [ ] RuneLite window fills the tablet screen (or scales to fill)
- [ ] Tap Launch → Termux:X11 opens automatically, RuneLite appears without manual switching

---

## Slice 3: Auth Works

**Auth approach confirmed**: Native in-app OAuth2 (Chrome Custom Tab + OkHttp for token exchange). Manual credentials.properties import is NOT implemented in this slice.

### Jagex OAuth2 Flow

Based on research from `.claude/research/authentication-solutions.md` and `.claude/research/jagex-launcher-auth.md`.

The flow has two distinct parts:

**Part A: Account Authentication (one browser session)**
- Standard OAuth2 authorization_code + PKCE
- Client ID: `com_jagex_auth_desktop_launcher`
- Documented redirect: `https://secure.runescape.com/m=weblogin/launcher-redirect`
- Scopes: `openid offline gamesso.token.create user.profile.read`
- Result: `access_token` (Step 1) + `refresh_token`

**Part B: Game Session (direct API calls, no browser)**
- Fetch character list: GET `https://auth.jagex.com/game-session/v1/accounts` with Step 1 `access_token`
- Create session: POST `https://auth.jagex.com/game-session/v1/sessions` with Step 1 `access_token` + selected character
- Result: `JX_SESSION_ID` (does not expire)

> **Critical open question**: The research documents a *second* OAuth2 browser flow (client ID `1fddee4e-b100-4f4e-b2b0-097f9088f9d2`, redirect to `http://localhost`) before game session creation. It is unknown whether this second browser redirect is actually required, or whether the game-session API calls can be made directly with the Step 1 access_token (which has `gamesso.token.create` scope). This must be resolved by Prerequisite Spike B before any auth code is written.

### Redirect URI Strategy

The documented redirect for Part A is `https://secure.runescape.com/m=weblogin/launcher-redirect` — a web URL, not an Android custom scheme. Two approaches to capture the code from Chrome Custom Tab:

**Option 1 (Try first): Custom scheme override**
Use `runelitetablet://auth` as the redirect URI. Android captures it via `<intent-filter>` in `AndroidManifest.xml`. Works if Jagex's OAuth2 server accepts non-documented redirect URIs for this client_id.

**Option 2 (Fallback): Intercept the web redirect**
Use the documented redirect URI. After the user logs in, Jagex redirects the browser to `secure.runescape.com/...?code=<code>`. The Chrome Custom Tab URL change can be detected via a `CustomTabsCallback`. Extract the auth code from the URL. Requires verifying Chrome Custom Tab supports URL-change callbacks with this redirect.

**Option 3 (Last resort for localhost redirect only): Local HTTP server**
If the second OAuth2 step truly requires `http://localhost`, start an ephemeral `ServerSocket` on a random port (`localhost:0`), include `http://localhost:<port>` as the redirect URI, capture the redirect in the server, then close it. Standard technique for OAuth2 on desktop; feasible on Android.

Prerequisite Spike A determines which option to use.

### Complete Auth Flow (Step by Step)

```
1. User taps "Sign in with Jagex"
2. App generates PKCE: code_verifier (64 random bytes, base64url) + code_challenge (SHA-256 → base64url)
3. App builds auth URL:
   https://account.jagex.com/oauth2/auth
     ?client_id=com_jagex_auth_desktop_launcher
     &response_type=code
     &redirect_uri=<chosen_redirect_uri>
     &scope=openid+offline+gamesso.token.create+user.profile.read
     &code_challenge=<code_challenge>
     &code_challenge_method=S256
     &state=<random_nonce>
4. App opens Chrome Custom Tab to auth URL
5. User logs in on Jagex website (handled entirely by browser/Jagex)
6. Jagex redirects to redirect_uri?code=<auth_code>&state=<state>
7. App captures auth_code via intent filter or URL-change callback
8. App verifies state matches nonce (CSRF check)
9. App POSTs to https://account.jagex.com/oauth2/token:
     grant_type=authorization_code
     code=<auth_code>
     redirect_uri=<same_redirect_uri>
     client_id=com_jagex_auth_desktop_launcher
     code_verifier=<code_verifier>    ← PKCE, no client_secret
10. Response: {access_token, refresh_token, token_type, expires_in}
11. (If second OAuth2 step needed — see Spike B) repeat steps 2–10 with second client_id
12. App GETs https://auth.jagex.com/game-session/v1/accounts
    Authorization: Bearer <access_token>
    Response: [{id, displayName, ...}, ...]
13. If >1 character: show CharacterSelectScreen, user picks one
14. App POSTs https://auth.jagex.com/game-session/v1/sessions
    Authorization: Bearer <access_token>
    Body: {characterId: <selected_id>}  (exact format: Spike B)
    Response: {sessionId: <JX_SESSION_ID>}
15. App stores in EncryptedSharedPreferences:
      JX_SESSION_ID, JX_CHARACTER_ID, JX_DISPLAY_NAME,
      JX_ACCESS_TOKEN, JX_REFRESH_TOKEN, access_token_expiry
16. Navigate to Launch screen
```

### PKCE Implementation

```kotlin
object PkceHelper {
    fun generateVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun deriveChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
```

`codeVerifier` lives in the ViewModel for the duration of the auth session. Discarded after token exchange (success or failure). Never persisted.

### Token Lifecycle

| Token | Lifespan | Action on expiry |
|---|---|---|
| `access_token` | ~1 hour (parse JWT `exp` claim) | Refresh silently before launch |
| `refresh_token` | ~30 days | Prompt full re-login |
| `JX_SESSION_ID` | Does not expire | No action needed |
| `JX_CHARACTER_ID` | Static | No action needed |
| `JX_DISPLAY_NAME` | Static | No action needed |

**Pre-launch token refresh** (runs before launch script dispatched):
```kotlin
suspend fun refreshIfNeeded(): AuthResult {
    val expiry = credentialManager.getAccessTokenExpiry()  // from stored JWT exp claim
    val now = System.currentTimeMillis() / 1000L
    if (now < expiry - 60) return AuthResult.Valid

    val refreshToken = credentialManager.getRefreshToken() ?: return AuthResult.NeedsLogin
    return try {
        val response = oAuth2Manager.refreshTokens(refreshToken)
        credentialManager.storeTokens(response)
        AuthResult.Refreshed
    } catch (e: HttpException) {
        if (e.code() == 401) AuthResult.NeedsLogin else AuthResult.NetworkError(e)
    }
}
```

### Credential Injection into Launch Script

Credentials are passed via a **temp file** — not via command-line arguments (which are visible in `ps` output and the Termux intent execution log).

**Kotlin** (writes temp file to app-private storage, passes path to script):
```kotlin
val creds = credentialManager.getCredentials() ?: return
val envFile = File(context.filesDir, "launch_env_${System.currentTimeMillis()}.sh")
envFile.writeText(buildString {
    appendLine("export JX_SESSION_ID='${creds.sessionId}'")
    appendLine("export JX_CHARACTER_ID='${creds.characterId}'")
    appendLine("export JX_DISPLAY_NAME='${creds.displayName}'")
    creds.accessToken?.let { appendLine("export JX_ACCESS_TOKEN='$it'") }
})
// Pass envFile.absolutePath as argument to launch script
```

**`launch-runelite.sh`** (sources and immediately deletes):
```bash
ENV_FILE="${1:-}"
if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    rm -f "$ENV_FILE"  # Delete immediately after sourcing
fi
# JX_* vars now available in shell scope, exported into proot below
```

Env file lives in `/data/data/com.runelitetablet/files/` — inaccessible to other apps without root. Deleted within milliseconds of use.

### EncryptedSharedPreferences — Initialization

Initialize lazily to survive Keystore unavailability (e.g., device locked after reboot):

```kotlin
class CredentialManager(private val context: Context) {
    private var _prefs: SharedPreferences? = null

    private fun getPrefs(): SharedPreferences? {
        if (_prefs != null) return _prefs
        return try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "jagex_credentials", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { _prefs = it }
        } catch (e: GeneralSecurityException) {
            null  // Surface as CredentialState.KeystoreUnavailable in ViewModel
        }
    }

    fun hasCredentials(): Boolean = getPrefs()?.getString("JX_SESSION_ID", null) != null
    fun getCredentials(): JagexCredentials? { /* ... */ }
    fun storeTokens(response: TokenResponse) { /* ... */ }
    fun clearCredentials() = _prefs?.edit()?.clear()?.apply().also { _prefs = null }
}
```

Never log credential values. All debug output masks them: `"JX_SESSION_ID=***"`.

### OAuth2 Error Handling

| Scenario | Behavior |
|---|---|
| User closes Chrome Custom Tab (back) | `RESULT_CANCELED` from Custom Tab → "Login cancelled" snackbar, return to ready state |
| Auth server returns `error` in redirect | Extract `error` + `error_description`, show human-readable message, offer retry |
| Token exchange HTTP 4xx/5xx | "Login failed — check internet connection", retry button |
| Token exchange timeout | "Connection timed out", retry button |
| Game session creation failure | "Could not start session", retry button |
| Accounts list empty | "No characters found on this account", offer re-login |
| Refresh token expired (HTTP 401) | Clear stored tokens, "Session expired — please log in again" |
| Keystore unavailable (locked device) | Catch `GeneralSecurityException` / `SecurityException`, "Device locked — unlock to continue" |

All errors recoverable without app restart. No error puts the app in an unrecoverable state.

### Manifest and Gradle Changes

**`app/build.gradle.kts`**:
```kotlin
dependencies {
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

**`AndroidManifest.xml`**:
```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Required for Chrome package visibility on Android 11+ -->
<queries>
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="https" />
    </intent>
</queries>

<activity
    android:name=".MainActivity"
    android:launchMode="singleTask">
    <!-- Custom scheme redirect (Option 1 — try first) -->
    <intent-filter android:label="RuneLite Auth">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="runelitetablet" android:host="auth" />
    </intent-filter>
</activity>
```

**`MainActivity`** — handle redirect in `onNewIntent()`:
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data ?: return
    if (uri.scheme == "runelitetablet" && uri.host == "auth") {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        viewModel.onAuthRedirect(code, state, error)
    }
}
```

### Prerequisite Spikes

These must be resolved **before implementation begins**. They are binary blockers.

**Spike A — Redirect URI strategy (30 minutes, on device)**

Test whether Jagex's OAuth2 server accepts `runelitetablet://auth` as the redirect URI for `com_jagex_auth_desktop_launcher`. Build a minimal Android app that launches the auth URL with a custom scheme redirect and observe the result.

- If custom scheme works → use Option 1 (simplest, standard Android pattern)
- If Jagex rejects unknown redirect URIs → use Option 2 (intercept `secure.runescape.com` redirect URL via Custom Tab URL-change callback)
- If neither works → use Option 3 (ephemeral localhost HTTP server) for any step that requires localhost redirect

**Spike B — Is second OAuth2 browser step required? (1 hour, with Postman + device)**

The research documents a second OAuth2 browser flow (client ID `1fddee4e-b100-4f4e-b2b0-097f9088f9d2`, redirect `http://localhost`) before game session creation. Test whether the game-session API accepts the Step 1 `access_token` (which has `gamesso.token.create` scope) directly:

```
POST https://auth.jagex.com/game-session/v1/sessions
Authorization: Bearer <step1_access_token>
```

- If this succeeds (returns `JX_SESSION_ID`) → **second browser step is not needed**. Simplest path.
- If this returns 401/403 → second OAuth2 browser step is required. Implement Option 3 (localhost server) for that step only.

**Spike C — Verify proot env var forwarding (10 minutes, in Termux terminal)**

In Termux: `export JX_SESSION_ID=test_value && proot-distro login ubuntu -- env | grep JX`

- If `JX_SESSION_ID=test_value` appears → env vars are inherited by proot; the env-file approach (sourcing in the outer shell before `proot-distro login`) works as designed
- If empty → env vars are stripped at proot boundary; source the env file inside the proot bash -c block instead

### Tasks

#### 3.1 — `JagexOAuth2Manager.kt` (NEW, `auth/` package)

- `buildAuthUrl(verifier: String): Uri` — constructs auth URL per flow diagram step 3
- `exchangeCodeForTokens(code: String, verifier: String, redirectUri: String): TokenResponse`
  - POST to token endpoint via OkHttp (`withContext(Dispatchers.IO)`)
  - Parse JSON response: `access_token`, `refresh_token`, `expires_in`
  - Parse JWT `exp` claim from `access_token` for expiry timestamp
- `refreshTokens(refreshToken: String): TokenResponse` — POST with `grant_type=refresh_token`
- `fetchCharacters(accessToken: String): List<Character>` — GET accounts endpoint
- `createGameSession(accessToken: String, characterId: String): String` — POST sessions endpoint, return `JX_SESSION_ID`
- If Spike B requires second OAuth2 step: `buildSecondAuthUrl()` + `exchangeSecondCode()`
- Never log credential values

#### 3.2 — Redirect URI Capture

Implement per Spike A result:
- **Option 1** (custom scheme): `AndroidManifest.xml` intent-filter + `onNewIntent()` → already specced above
- **Option 2** (web redirect intercept): `CustomTabsCallback` + URL-change detection
- **Option 3** (localhost server, only if needed for second step): `ServerSocket(0)` on localhost, capture redirect, close server

#### 3.3 — `CredentialManager.kt` (NEW, `auth/` package)

- Lazy `EncryptedSharedPreferences` initialization (see above)
- Store/retrieve: `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`, `JX_ACCESS_TOKEN`, `JX_REFRESH_TOKEN`, `access_token_expiry` (Long, Unix seconds)
- `hasCredentials(): Boolean`
- `getCredentials(): JagexCredentials?`
- `getAccessTokenExpiry(): Long`
- `clearCredentials()`
- **No import timestamp** — token freshness determined by JWT `exp` claim, not calendar heuristic

#### 3.4 — `CharacterSelectScreen.kt` (NEW, `ui/` package)

Shown if multiple characters returned from accounts endpoint:
- List of characters with display name (and account type if available)
- "Play as [Name]" button per character
- Single character: auto-select, skip screen
- Stores selected `JX_CHARACTER_ID` + `JX_DISPLAY_NAME` via `CredentialManager`

#### 3.5 — Auth Integration into App Flow

Navigation:
1. Setup step 7 completes → check `credentialManager.hasCredentials()`
2. No credentials → show `JagexLoginScreen` ("Sign in with Jagex" button + "Skip" link)
3. Login completes → `CharacterSelectScreen` if needed → Launch screen
4. On Launch tap:
   a. `refreshIfNeeded()` — await
   b. `NeedsLogin` → navigate to login with "Session expired" message
   c. `Valid` or `Refreshed` → write env file → launch Termux:X11 → poll socket → fire script

"Skip" link: user can bypass auth. RuneLite shows its own login screen. No credentials passed to script.

#### 3.6 — `SettingsScreen.kt` (NEW, `ui/` package)

Accessible via top-right overflow menu on Launch screen:

**Account section**:
- Shows `JX_DISPLAY_NAME` (or "Not signed in")
- "Sign Out" button → `credentialManager.clearCredentials()`, navigate to login screen

**Setup section**:
- "Reset Setup" button → `stateStore.clearAll()` + run `clear-markers.sh` in Termux → navigate to setup wizard step 1

**About section**:
- App version, RuneLite version (if known)

#### 3.7 — Modify `launch-runelite.sh`

- Add env file sourcing at top (see Credential Injection section)
- Update `export` block to pass `JX_*` vars into proot bash invocation
- Add xrandr resolution setup (per Task 2.6)
- Do NOT echo credential values in any log/debug output

### Exit Criteria

- [ ] "Sign in with Jagex" opens Chrome Custom Tab at the correct Jagex auth URL with PKCE parameters
- [ ] Completing Jagex login redirects back to the app automatically
- [ ] Character selection shown if multiple characters; single character auto-selected
- [ ] Tokens stored in EncryptedSharedPreferences and survive app restart
- [ ] Launch with valid credentials → RuneLite opens to character select or game world without showing Jagex login screen
- [ ] Access token refresh runs silently before launch when near expiry
- [ ] Refresh token expiry → "Session expired" prompt, user can re-login
- [ ] "Sign Out" in Settings clears credentials; next launch prompts sign-in
- [ ] "Reset Setup" clears all state; setup wizard shown from step 1
- [ ] No JX_* values appear in logcat at any log level

---

## Resolved Decisions

| # | Question | Decision |
|---|---|---|
| U1 | Auth approach | **Native in-app OAuth2** (Chrome Custom Tab + OkHttp) |
| U2 | State persistence | **Both** — marker files (ground truth) + SharedPreferences (UI cache) |
| U3 | Credential import | **N/A** — replaced by OAuth2 |
| U4 | UX defects in this slice? | **Yes** — included in Slice 2 |
| U5 | UX goal | **Seamless** — open app, tap Launch, RuneLite appears. No Termux interaction ever. |

---

## Implementation Order

**Do spikes first** (parallel with Slice 2 coding):
1. Spike C: proot env var forwarding (10 min on device)
2. Spike A: redirect URI strategy (30 min on device)
3. Spike B: second OAuth2 step required? (1 hour, Postman + device)

**Slice 2** (foundation first):
4. Three modular shell scripts (`install-proot.sh`, `install-java.sh`, `download-runelite.sh`, `check-markers.sh`)
5. `SetupStateStore.kt`
6. Refactor `SetupOrchestrator` (delete `setupScriptRan`, add modular dispatch, suspend reconciliation)
7. UX: X11 resolution fix + seamless Termux:X11 launch sequence

**Slice 3** (after spikes resolved):
8. Manifest + Gradle changes
9. `JagexOAuth2Manager.kt` (PKCE, token exchange, game session)
10. Redirect URI capture (implementation per Spike A result)
11. `CredentialManager.kt` (secure storage, token lifecycle)
12. `CharacterSelectScreen.kt`
13. Auth integration into launch flow + token refresh
14. `SettingsScreen.kt`
15. `launch-runelite.sh` — credential injection + xrandr

**End-to-end test**:
16. Fresh install → setup → Jagex login → character select → Launch → RuneLite running, logged in

---

## Deferred (Not in This Plan)

- Audio passthrough (PulseAudio in proot)
- GPU acceleration (Mesa Zink + Turnip)
- RuneLite auto-update (GitHub releases check)
- Distributable release (Play Store, multi-user support)
- Full Polish screen (log viewer, health checks)

# GeckoView Auth Integration Design

**Date**: 2026-02-23
**Status**: Approved — Ready for Implementation
**Replaces**: Chrome Custom Tabs + LocalhostAuthServer for OAuth redirect capture

## Context

GeckoView (Firefox engine) was validated in Session 32 as a Cloudflare-compatible browser engine on Android. Its `NavigationDelegate.onLoadRequest()` can intercept redirects at the engine level before network requests, solving the port 80 blocker for Step 2 consent. This design replaces Chrome Custom Tabs and the localhost server with a single GeckoView-based auth Activity.

## Design Decisions

| Decision | Choice |
|----------|--------|
| UI structure | Dedicated `GeckoAuthActivity` |
| Fallback | None — GeckoView only, no Chrome Custom Tabs fallback |
| Communication | ActivityResult API |
| Step flow | Single launch, both steps in one GeckoView session |
| Logic split | Activity does token exchange + login_provider check internally |
| Cancellation | Return RESULT_CANCELED immediately on back press |

## Architecture

### GeckoAuthActivity — Intent Contract

**Input (Intent Extras)**:

```kotlin
companion object {
    const val EXTRA_STEP1_URL = "step1_url"        // Required: Step 1 auth URL
    const val EXTRA_STEP2_URL = "step2_url"        // Required: Step 2 consent URL
    const val EXTRA_CODE_VERIFIER = "code_verifier" // Required: PKCE verifier for token exchange
    const val EXTRA_STEP1_STATE = "step1_state"     // Required: CSRF state for Step 1
    const val EXTRA_STEP2_STATE = "step2_state"     // Required: CSRF state for Step 2
    const val EXTRA_NONCE = "nonce"                  // Required: Nonce for Step 2 id_token
}
```

**Output (Result Extras)**:

```kotlin
// RESULT_OK extras:
const val RESULT_LOGIN_PROVIDER = "login_provider"       // "jagex" or "runescape"
const val RESULT_ACCESS_TOKEN = "access_token"
const val RESULT_REFRESH_TOKEN = "refresh_token"
const val RESULT_ID_TOKEN = "id_token"                   // Step 1 (runescape) or Step 2 (jagex)
const val RESULT_EXPIRES_IN = "expires_in"
const val RESULT_ACCESS_TOKEN_EXPIRY = "access_token_expiry"

// RESULT_CANCELED — user dismissed
// RESULT_FIRST_USER extras (error case):
const val RESULT_ERROR = "error"                         // Error message string
```

### Internal State Machine

```
LOADING_STEP1 → AWAITING_STEP1_REDIRECT → EXCHANGING_TOKENS
    → (if runescape) DONE_SUCCESS
    → (if jagex) LOADING_STEP2 → AWAITING_STEP2_REDIRECT → DONE_SUCCESS

Any state → ERROR / CANCELLED
```

### GeckoRuntime Singleton

```kotlin
companion object {
    private var sRuntime: GeckoRuntime? = null

    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        return sRuntime ?: GeckoRuntime.create(
            context.applicationContext,  // App context, not Activity
            GeckoRuntimeSettings.Builder()
                .consoleOutput(BuildConfig.DEBUG)
                .build()
        ).also { sRuntime = it }
    }
}
```

- Uses `applicationContext` to avoid Activity leak
- Singleton survives Activity recreation
- Never call `runtime.shutdown()` — it's process-lifetime

## NavigationDelegate — Redirect Interception

### onLoadRequest Logic

```kotlin
override fun onLoadRequest(session, request): GeckoResult<AllowOrDeny>? {
    val uri = request.uri

    when {
        // Step 1: jagex: scheme redirect from launcher-redirect page
        uri.startsWith("jagex:") -> {
            val params = parseJagexUri(uri)
            val code = params["code"]
            val state = params["state"]

            if (code != null && state == expectedStep1State) {
                onStep1CodeCaptured(code)
            } else {
                onError("Invalid Step 1 redirect: missing code or state mismatch")
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        // Step 2: http://localhost redirect with fragment
        uri.startsWith("http://localhost") -> {
            val fragment = uri.substringAfter("#", "")
            val params = parseFragmentParams(fragment)
            val idToken = params["id_token"]
            val state = params["state"]

            if (idToken != null && state == expectedStep2State) {
                onStep2TokenCaptured(idToken)
            } else {
                onError("Invalid Step 2 redirect: missing id_token or state mismatch")
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        // Everything else: allow navigation
        else -> return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }
}
```

### URL Routing

| URL Pattern | Action | Why |
|-------------|--------|-----|
| `jagex:*` | DENY | Custom scheme — extract code, no page to load |
| `http://localhost*` | DENY | Port 80 blocked, we only need the params |
| `https://account.jagex.com/*` | ALLOW | Login pages, Cloudflare challenge |
| `https://secure.runescape.com/*` | ALLOW | Launcher-redirect page (triggers jagex: redirect) |
| Everything else | ALLOW | Supporting resources, CDN, etc. |

### URI Parsing

**`parseJagexUri(uri)`** — handles `jagex:code=XXX,state=YYY,intent=social_auth`
- Strip `jagex:` prefix
- Split on both `,` and `&` (Jagex uses comma separators, be defensive for both)
- URL-decode keys and values

**`parseFragmentParams(fragment)`** — handles `id_token=XXX&code=YYY&state=ZZZ`
- Standard `&`-separated key=value pairs
- URL-decode keys and values

## SetupViewModel Changes

### New Auth Flow

```kotlin
// Registration in MainActivity, exposed via SetupActions
private val authLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result -> viewModel.handleAuthResult(result) }

// In SetupViewModel:
fun startLogin() {
    val codeVerifier = PkceHelper.generateVerifier()
    val step1State = PkceHelper.generateState()
    val step2State = PkceHelper.generateState()
    val nonce = generateNonce(48)

    val step1Url = oauthManager.buildStep1AuthUrl(codeVerifier, step1State)
    val step2Url = oauthManager.buildStep2ConsentUrl(step2State, nonce)

    pendingNonce = nonce  // Store for verification on result

    val intent = GeckoAuthActivity.createIntent(
        context, step1Url, step2Url, codeVerifier,
        step1State, step2State, nonce
    )
    actions.launchAuthActivity(intent)
}

fun handleAuthResult(result: ActivityResult) {
    when (result.resultCode) {
        RESULT_OK -> {
            // Extract tokens from result extras
            // Verify nonce on Step 2 id_token if login_provider == "jagex"
            // Store tokens via CredentialManager
            // Continue to Step 3: createGameSession(idToken)
        }
        RESULT_CANCELED -> onAuthError("Login cancelled")
        else -> onAuthError(result.data?.getStringExtra(RESULT_ERROR) ?: "Login failed")
    }
}
```

### SetupActions Interface Changes

**Add**:
```kotlin
fun launchAuthActivity(intent: Intent)
fun registerAuthCallback(callback: (ActivityResult) -> Unit)
```

### Code Removed from SetupViewModel

- `handleJagexRedirect()` — GeckoView captures jagex: redirects
- `runStep2Consent()` — GeckoAuthActivity handles Step 2 internally
- `startPort80Forwarder()` / `stopPort80Forwarder()` — no more socat
- `checkLoginDismissal()` — ActivityResult handles cancellation
- `buildCustomTabIntent()` / `getChromePackage()` — no more Custom Tabs
- `step1Deferred` / `awaitingStep1Auth` / `activeConsentServer` fields

### Code Removed from MainActivity

- `onNewIntent()` handler for `jagex:` URIs

## Cleanup — Deletions

### Files Deleted

| File | Lines | Reason |
|------|-------|--------|
| `auth/GeckoViewTestActivity.kt` | 98 | Replaced by production `GeckoAuthActivity.kt` |
| `auth/AuthRedirectCapture.kt` | 564 | Entire localhost server + HTML forwarder eliminated |

### AndroidManifest.xml Changes

**Remove**:
- `http://localhost` intent filter from MainActivity
- `GeckoViewTestActivity` registration

**Add**:
- `GeckoAuthActivity` registration (`android:exported="false"`)

**Keep**:
- `jagex:` intent filter on MainActivity (safety net if Chrome somehow opens)

### build.gradle.kts Changes

**Remove**: `implementation("androidx.browser:browser:1.8.0")`
**Keep**: GeckoView dependency (already present)

## Error Handling

| Error | Trigger | Handling |
|-------|---------|----------|
| GeckoRuntime init failure | Device incompatibility, OOM | `setResult(error)` + `finish()` |
| Cloudflare block | Shouldn't happen (verified passing) | User sees error page, can back out → CANCELED |
| Step 1 state mismatch | CSRF attack or corrupted redirect | `setResult(error)` with "Security check failed" |
| Step 1 missing code | Malformed `jagex:` URI | `setResult(error)` with "Invalid login redirect" |
| Token exchange failure | Network error, invalid code, server error | `setResult(error)` with server error message |
| Step 2 state mismatch | CSRF attack or corrupted redirect | `setResult(error)` with "Security check failed" |
| Step 2 missing id_token | Fragment not captured | `setResult(error)` with "Could not capture consent token" |
| Timeout | User sits on login page | No timeout — Jagex auth URLs have server-side expiry |
| Back press | User cancels | `RESULT_CANCELED` |

### Fragment Capture Contingency

If `onLoadRequest()` does NOT include `#fragment` (needs on-device verification):

1. **First try**: `onLocationChange(session, url)` — may include fragment
2. **Second try**: `session.evaluateJavascript("window.location.href")` after brief navigation
3. **Last resort**: Minimal localhost server on ephemeral port (no socat/port 80 needed)

### Token Exchange Lifecycle Safety

- `lifecycleScope.launch` auto-cancels on `onDestroy()`
- OkHttp cancelled via `suspendCancellableCoroutine` + `invokeOnCancellation`
- User retries with fresh PKCE codes

## Implementation Phases

### Phase 1: Create GeckoAuthActivity
- New file `auth/GeckoAuthActivity.kt` (~250 lines)
- Intent contract, GeckoRuntime singleton, GeckoSession + NavigationDelegate
- `parseJagexUri()` and `parseFragmentParams()` helpers
- Internal state machine, token exchange, back press handling

### Phase 2: Wire SetupViewModel + MainActivity
- Add `launchAuthActivity()` + `registerAuthCallback()` to `SetupActions`
- Register `ActivityResultLauncher` in `MainActivity`
- Replace `startLogin()` in SetupViewModel
- Add `handleAuthResult()` handler

### Phase 3: Cleanup
- Delete `auth/AuthRedirectCapture.kt` and `auth/GeckoViewTestActivity.kt`
- Remove dead code from SetupViewModel (~400 lines)
- Remove `onNewIntent()` jagex: handling from MainActivity
- Update AndroidManifest.xml and build.gradle.kts

### Phase 4: On-Device Verification
- Full flow: Step 1 → Cloudflare → login → jagex: capture → token exchange → Step 2 → localhost fragment capture → game session → character selection
- Verify fragment capture in `onLoadRequest()`
- Test cancellation and error cases
- Implement contingency if fragment missing

## File Change Summary

| File | Action | ~Lines |
|------|--------|--------|
| `auth/GeckoAuthActivity.kt` | **NEW** | +250 |
| `setup/SetupViewModel.kt` | MODIFY | -400, +80 |
| `MainActivity.kt` | MODIFY | -10, +20 |
| `auth/AuthRedirectCapture.kt` | **DELETE** | -564 |
| `auth/GeckoViewTestActivity.kt` | **DELETE** | -98 |
| `AndroidManifest.xml` | MODIFY | ~5 |
| `app/build.gradle.kts` | MODIFY | -1 |

**Net result**: ~300 fewer lines of code

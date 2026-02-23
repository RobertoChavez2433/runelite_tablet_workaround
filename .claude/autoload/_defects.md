# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [AUTH-BLOCKER] 2026-02-23: OAuth uses wrong client_id for Step 1 — Jagex rejects with "something went wrong"
**Pattern**: Our `JagexOAuth2Manager.kt` uses `1fddee4e-b100-4f4e-b2b0-097f9088f9d2` (the Step 2 consent client) for the initial browser login (Step 1). This client_id is only registered for the consent/game-session step with `http://localhost` redirect and `openid offline` scopes. Jagex's OAuth server returns a generic "Sorry, something went wrong" error page instead of the login form.
**Root Cause**: Jagex OAuth is a **two-step, two-client-ID flow**. Step 1 must use `com_jagex_auth_desktop_launcher` with redirect to `https://secure.runescape.com/m=weblogin/launcher-redirect`. The `launcher-redirect` page triggers a `jagex:` custom protocol redirect that desktop launchers capture. Step 2 then uses `1fddee4e-...` with `http://localhost`.
**Fix Required**: (1) Register `jagex:` intent scheme in AndroidManifest.xml. (2) Step 1: use `com_jagex_auth_desktop_launcher` + launcher-redirect. (3) Capture `jagex:code=...&state=...` via intent filter Activity. (4) Step 2: use `1fddee4e-...` + localhost with `response_type=id_token code` (implicit/hybrid flow). (5) Capture Step 2 via localhost server with forwarder HTML for fragment extraction.
**Ref**: `.claude/research/jagex-oauth2-two-step-flow.md`, @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

### [ANDROID] 2026-02-23: Android clipboard corrupts single quotes when pasting into Termux
**Pattern**: Copying shell commands with single quotes (`'`) from Android clipboard to Termux via long-press paste. Android or the keyboard may substitute curly/smart quotes (`'` `'`) for straight quotes, breaking shell syntax. Bash sees an unclosed quote and waits for more input (`>` prompt).
**Prevention**: Avoid single quotes in commands users must paste. Use no-quote alternatives when the string has no shell special chars (e.g., `echo allow-external-apps=true` needs no quotes). If quotes are unavoidable, use double quotes (less likely to be corrupted).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt

### [ANDROID] 2026-02-23: `@Volatile var` is not reactive — StateFlow derivations won't re-evaluate
**Pattern**: Using `@Volatile var` for a boolean flag (`awaitingPermissionCompletion`) that is read inside a `Flow.map{}` or `combine{}` derivation. The flow only re-evaluates when its source flows emit — changes to the volatile var don't trigger any emission. Result: derived `StateFlow<Boolean>` stays at its initial value forever.
**Prevention**: Use `MutableStateFlow<Boolean>` instead of `@Volatile var` for any state read by reactive flow derivations. Combine it with `combine()` so changes trigger re-evaluation.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [ANDROID] 2026-02-23: Sealed class companion `val` can be null during static init (ART)
**Pattern**: `SetupStep.allSteps` was an eagerly initialized `val` in the companion object referencing sealed subclass objects (`InstallTermux`, etc.). On ART, the companion init can run before all `object` subclasses are constructed, resulting in null entries in the list. Crashes with `NullPointerException: parameter step is non-null`.
**Prevention**: Use `by lazy` for companion `val` properties that reference sealed class object subclasses. This defers evaluation until first access, when all objects are guaranteed initialized.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupStep.kt

### [ANDROID] 2026-02-23: Android WebView is fundamentally incompatible with Cloudflare — cannot be fixed
**Pattern**: Cloudflare uses multi-layer detection that WebView cannot pass: (1) TLS fingerprint mismatch — WebView lacks Chrome's ClientHello extension permutation, so JA3/JA4 hash doesn't match the Chrome UA. (2) `X-Requested-With: <package>` header — Turnstile always fails with this header. (3) Canvas/Picasso fingerprint differs from Chrome. (4) Missing JS APIs (`navigator.plugins`, `window.chrome`, `ServiceWorker`). Stripping `; wv` from UA makes it WORSE by creating a UA/TLS inconsistency Cloudflare flags with higher confidence.
**Prevention**: Never use Android WebView for Cloudflare-protected pages. Use Chrome Custom Tabs (runs in Chrome process, same TLS/canvas/JS) or system browser. This is an industry-wide issue — even Jagex's own CEF-based launcher has Cloudflare problems.
**Ref**: `.claude/plans/2026-02-23-custom-tabs-auth-design.md`

### [ANDROID] 2026-02-23: Chrome Custom Tabs onNavigationEvent URL extra is unreliable
**Pattern**: `CustomTabsCallback.onNavigationEvent(NAVIGATION_STARTED)` is supposed to include the URL in the extras Bundle via `android.support.customtabs.extra.URL`, but Chrome does not guarantee this extra is present on all devices/versions. Also, if the Custom Tab is launched before `onCustomTabsServiceConnected` fires, the session callback is never attached to the tab.
**Prevention**: Never rely on Custom Tabs callback for capturing OAuth redirects. Use a localhost `ServerSocket` to capture the redirect — fully under app control, no Chrome dependency. Already proven reliable for both auth steps.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/AuthRedirectCapture.kt

### [SECURITY] 2026-02-23: Kotlin data class toString() leaks sensitive fields by default
**Pattern**: Kotlin `data class` auto-generates `toString()` including ALL fields. If a data class holds tokens, credentials, or auth codes, any accidental logging (crash reports, exception interpolation, debug logs) exposes plaintext secrets.
**Prevention**: Always add `override fun toString() = "ClassName([REDACTED])"` to any data class holding sensitive fields. Check: JagexCredentials, TokenResponse, AuthCodeResult.Success, OAuthException.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt

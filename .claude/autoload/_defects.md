# Active Defects

Max 7 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [AUTH] 2026-02-23: Game session API calls use wrong auth method — accessToken vs id_token/sessionId
**Pattern**: `fetchCharacters()` and `createGameSession()` pass `accessToken` as Bearer header. Real flow: POST `{"idToken":"<jwt>"}` to `/sessions` (returns sessionId), then GET `/accounts` with Bearer `<sessionId>`. Order is also reversed — `/sessions` must come before `/accounts`.
**Prevention**: Verify API call signatures against reference implementations (aitoiaita `game_session.rs`). The adversarial reviewer caught this.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

### [AUTH] 2026-02-23: Consent client_id cannot initiate standalone login — returns unsupported_response_type
**Pattern**: Using `1fddee4e-...` consent client_id with `response_type=code` or `id_token code` and `redirect_uri=http://localhost` for the initial OAuth login. Jagex server returns `unsupported_response_type` error. The consent client only works for Step 2 after Step 1 session is established.
**Prevention**: Always use `com_jagex_auth_desktop_launcher` for Step 1. Consent client is Step 2 only. Verified by on-device test (Session 29).
**Ref**: `.claude/plans/2026-02-23-oauth-2step-rewrite-design.md`

### [AUTH-BLOCKER] 2026-02-23: OAuth uses wrong client_id for Step 1 — Jagex rejects with "something went wrong"
**Pattern**: Our `JagexOAuth2Manager.kt` uses `1fddee4e-b100-4f4e-b2b0-097f9088f9d2` (the Step 2 consent client) for the initial browser login (Step 1). Jagex's OAuth server returns a generic "Sorry, something went wrong" error page.
**Fix Required**: Implement correct 2-step flow. Design approved: `.claude/plans/2026-02-23-oauth-2step-rewrite-design.md`
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

### [ANDROID] 2026-02-23: Android clipboard corrupts single quotes when pasting into Termux
**Pattern**: Android or keyboard substitutes curly/smart quotes for straight quotes, breaking shell syntax.
**Prevention**: Avoid single quotes in commands users must paste. Use no-quote alternatives or double quotes.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt

### [ANDROID] 2026-02-23: `@Volatile var` is not reactive — StateFlow derivations won't re-evaluate
**Pattern**: `@Volatile var` read inside `Flow.map{}` or `combine{}` won't trigger re-evaluation when changed.
**Prevention**: Use `MutableStateFlow<Boolean>` instead. Combine with `combine()` for reactive derivations.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [ANDROID] 2026-02-23: Sealed class companion `val` can be null during static init (ART)
**Pattern**: Eagerly initialized `val` in companion referencing sealed subclass objects can be null during ART static init.
**Prevention**: Use `by lazy` for companion `val` properties referencing sealed class object subclasses.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupStep.kt

### [ANDROID] 2026-02-23: Android WebView is fundamentally incompatible with Cloudflare — cannot be fixed
**Pattern**: Cloudflare multi-layer detection (TLS fingerprint, X-Requested-With, canvas, missing JS APIs) blocks WebView permanently.
**Prevention**: Never use WebView for Cloudflare-protected pages. Use Chrome Custom Tabs or system browser.
**Ref**: `.claude/plans/completed/2026-02-23-custom-tabs-auth-design.md`

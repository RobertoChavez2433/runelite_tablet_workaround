# Session State

**Last Updated**: 2026-02-23 | **Session**: 28

## Current Phase
- **Phase**: MVP Development — On-Device Testing & Bug Fixing
- **Status**: OAuth login broken — wrong client_id used for Step 1. Correct 2-step flow researched and documented. Custom Tabs + Cloudflare fix confirmed working (Chrome opens, Jagex page loads). Sessions 25-26 code uncommitted. Session 28 research only (no code changes).

## HOT CONTEXT - Resume Here

### EXACTLY WHERE WE LEFT OFF

**Session 28: Discovered OAuth uses wrong client_id. Researched correct 2-step flow. No code changes.**

On-device test confirmed:
- Chrome Custom Tabs works (Cloudflare passes)
- Jagex returns "Sorry, something went wrong" — server-side rejection

Root cause: Our code uses `1fddee4e-...` (Step 2 consent client) for Step 1 (initial login). Jagex OAuth is a **two-step, two-client-ID flow**:

| Step | Client ID | Redirect | Scopes |
|------|-----------|----------|--------|
| 1. Account auth | `com_jagex_auth_desktop_launcher` | `launcher-redirect` → `jagex:` protocol | `openid offline gamesso.token.create user.profile.read` |
| 2. Consent | `1fddee4e-b100-4f4e-b2b0-097f9088f9d2` | `http://localhost` | `openid offline` (response_type=`id_token code`) |

The `launcher-redirect` page triggers a `jagex:` custom protocol redirect. On Android, register an intent filter for `jagex:` scheme to capture the auth code.

### What Needs to Happen Next

1. **Rewrite OAuth to correct 2-step flow** — see `.claude/research/jagex-oauth2-two-step-flow.md`
   - Step 1: `com_jagex_auth_desktop_launcher` + `jagex:` intent scheme capture
   - Step 2: `1fddee4e-...` + localhost + implicit/hybrid flow with forwarder HTML
   - AndroidManifest: register `jagex:` scheme handler Activity
   - LocalhostAuthServer: add POST handling for `/authcode`, forwarder HTML for Step 2 fragment capture
2. **Test on device** — verify full login flow completes
3. **Commit** all changes from Sessions 25-28

## Blockers

- **OAuth client_id mismatch** — Step 1 needs `com_jagex_auth_desktop_launcher` with `jagex:` protocol capture. Full design in `.claude/research/jagex-oauth2-two-step-flow.md`.

## Recent Sessions

### Session 28 (2026-02-23)
**Work**: Built and installed app on tablet. Custom Tabs auth opened Chrome successfully (Cloudflare passed). Jagex returned "Sorry, something went wrong" — server-side rejection. Researched 3 open-source launchers (aitoiaita, melxin, Bolt) via `gh api` to extract exact OAuth parameters. Discovered Jagex uses 2-step, 2-client-ID flow: Step 1 uses `com_jagex_auth_desktop_launcher` with `launcher-redirect` → `jagex:` protocol, Step 2 uses `1fddee4e-...` with localhost. Wrote comprehensive research doc. Updated defects with new blocker.
**Decisions**: Must rewrite OAuth to correct 2-step flow. `jagex:` intent scheme for Step 1 capture. No more guessing at parameters — all values verified from working implementations.
**Next**: Rewrite OAuth flow, test on device, commit.

### Session 27 (2026-02-23)
**Work**: Deep research into Cloudflare WebView block (5 parallel agents). Discovered root cause: TLS fingerprint mismatch, X-Requested-With header, canvas fingerprinting, missing JS APIs. Stripping `; wv` actually worsened detection. Researched Bolt, melxin, aitoiaita, rislah launchers. Brainstormed 3 approaches, designed Chrome Custom Tabs + localhost solution.
**Decisions**: Delete AuthWebViewActivity, use Chrome Custom Tabs with second client_id + localhost redirect for both OAuth steps. WebView is unfixable for Cloudflare.
**Next**: Implement Custom Tabs auth design, test on device, commit Sessions 25-27, Slice 4+5.

### Session 26 (2026-02-23)
**Work**: Implemented Phase 1 UX revert via `/implement` (clipboard copy-paste + extra keys config). Fixed 3 on-device issues: (1) auto-return via `am start` in pasted command, (2) TermuxConfig phase advance without runtime permission, (3) CRLF line endings in 5 shell scripts breaking shebang. Full setup flow verified on device — all 7 steps pass. Login hit Cloudflare block.
**Decisions**: `am start` for smart auto-return (not hardcoded delay), unconditional Phase 1 advance, `.gitattributes` for LF enforcement, defensive `\r` stripping in ScriptManager.
**Next**: Wait for Cloudflare block to expire, test login, commit, Slice 4+5.

### Session 25 (2026-02-23)
**Work**: On-device test found 3 bugs: StepState NPE (lazy init fix), SecurityException in verifyPermissions (catch), isPermissionStepActive always false (MutableStateFlow). Fixed all 3. Iterated on Phase 1 UX — clipboard quotes corrupted, simplified command, tried MediaStore typing approach (user rejected).
**Decisions**: Use `by lazy` for sealed class companion vals, MutableStateFlow for reactive boolean state, no-quotes command for Termux config. User prefers copy-paste over typing.
**Next**: Revert MediaStore UI to copy-paste, finish on-device test, commit, Slice 4+5.

### Session 24 (2026-02-23)
**Work**: Fixed Cloudflare WebView block (remove `; wv` UA token). Brainstormed + implemented permissions automation (5 phases, 5 files, 6 quality gates, 4 P1s fixed).
**Decisions**: Copy-paste flow for Termux config (can't automate), auto-poll on resume, permissions before Termux work, strip `; wv` from WebView UA.
**Next**: On-device test of permissions + login, commit, then Slice 4+5.

## Active Plans

- **Phase 1 UX Revert + Extra Keys** — **COMPLETE**. Verified on device.
- **Permissions Automation** — **COMPLETE**. All 3 phases work, auto-advance verified.
- **Custom Tabs Auth (Cloudflare Fix)** — **NEEDS REWRITE**. Custom Tabs work (Cloudflare passes), but wrong client_id for Step 1. Must implement correct 2-step flow with `jagex:` protocol capture.
- **OAuth Login Fix** — **SUPERSEDED by Custom Tabs rewrite**.
- **Security Hardening** — **COMPLETE**. All 15 findings fixed and verified.
- **Slice 4+5 Implementation** — **DESIGNED**. Plan committed. Blocked by auth fix.
- **Slice 2+3 Implementation** — **COMPLETE**. Committed + security hardened + OAuth fixed.

## Reference
- **OAuth 2-step flow research**: `.claude/research/jagex-oauth2-two-step-flow.md` (**NEW** — key reference for rewrite)
- **Custom Tabs auth plan**: `.claude/plans/2026-02-23-custom-tabs-auth-design.md` (partially correct — Custom Tabs part good, client_id wrong)
- **Slice 4+5 plan**: `.claude/plans/2026-02-23-slice4-5-implementation-plan.md`
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Research**: `.claude/research/` (7 files + README)

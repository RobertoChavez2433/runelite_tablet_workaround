# State Archive

Session history archive. See `.claude/autoload/_state.md` for current state (last 5 sessions).

---

## March 2026

### Session 46 (2026-03-09)
**Work**: On-device test found 3 bugs: (1) Step 1 tokens discarded for Jagex accounts in GeckoAuthActivity Step 2 result, (2) env file race — old script EXIT trap deletes file before new script reads it, (3) HTTP 400 `invalid_grant` not treated as NeedsLogin. All 3 fixed and verified — env file now 343 bytes with JX_ACCESS_TOKEN. Game server still rejects login.
**Decisions**: Move credential sourcing before cleanup in launch-runelite.sh. Treat HTTP 400 same as 401 for refresh rejection. Save Step 1 tokens in GeckoAuthActivity instance fields for Step 2 passthrough.
**Next**: Research what token OSRS game server actually needs (BLOCKER). GPU test after login. Commit fixes.

### Session 45 (2026-03-09)
**Work**: Implemented auth session refresh fix via `/implement` (3 phases, 6 quality gates, 0 handoffs). Added `SessionValidation` sealed class, `validateSession()`, `pendingLaunchAfterAuth` auto-resume, `LaunchState.ValidatingSession` UI state. Zero review findings.
**Decisions**: `navigateToLaunchOrResume()` helper to DRY auth completion logic across 3 paths. Added missing `CancellationException` import.
**Next**: On-device test of auth flow, GPU plugin test, commit changes.

### Session 43 (2026-03-09)
**Work**: Implemented .claude directory upgrade via `/implement` (12 phases, all passed). Created ~50 new files. 2 review agents found 4 broken-reference issues (security package gap, stale research count, missing agent memory paths). All fixed.
**Decisions**: Used `/implement` for markdown-only changes. "Security" treated as cross-cutting package with own constraints/docs. All 5 agents now declare memory paths.
**Next**: Auth session refresh fix (P0), GPU test after login, optional `/audit-config`.

## March 2026

### Session 42 (2026-03-09)
**Work**: Audited FieldGuide vs Tablite .claude directories (6 parallel agents). Brainstormed 12-section upgrade design (8 questions). Approved: 2 new agents, rules extraction, per-feature defects, constraints, agent memory, feature docs, writing-plans/adversarial-review/audit-config skills. Design doc saved.
**Decisions**: Incremental migration approach. Extract existing content (no new invented content). Per-feature defects replace single _defects.md. Adversarial review as standalone skill. End/resume session skills to match FieldGuide patterns.
**Next**: Implement .claude upgrade (12 steps), then auth fix, then GPU test.

## March 2026

### Session 41 (2026-03-08)
**Work**: On-device test. GPU setup passes (virtio_gpu_dri.so present, lfdevs Mesa works). RuneLite launches but OSRS login fails. Diagnosed: refresh flow incomplete (only Step 1/3). JX_SESSION_ID expired after 12 days. Verified via 4 API tests. Fix plan written.
**Decisions**: Game session cannot be recreated without browser (Step 2 consent). Pre-launch session validation + auto-reauth is the fix. JX_SESSION_ID DOES expire (corrects prior assumption).
**Next**: Implement auth reauth fix, test GPU plugin after login works, production P1s.

## March 2026

### Session 40 (2026-02-24)
**Work**: Brainstormed GPU blocker fix (3 research agents). Designed lfdevs Mesa approach. Implemented via /implement (1 phase, 6 gates). Audited MEMORY.md (11 issues, 5 fixed). Fixed SoC reference (Snapdragon→MediaTek). 3 commits.
**Decisions**: Use lfdevs Mesa (same project as Adreno path, includes virgl). No distro change. Pin Mesa 26.1.0. VirGL is only viable Mali GPU path in proot.
**Next**: On-device test GPU setup + VirGL, then RuneLite GPU plugin, then production P1s.

---

## February 2026

### Session 39 (2026-02-24)
**Work**: First on-device test session. Fixed 3 bugs: shell syntax (16 unescaped `"` in bash -c block), env file premature deletion, stateStore cache not cleared on ABSENT reconciliation. GPU packages install correctly but VirGL doesn't work — Ubuntu ARM64 Mesa missing virpipe driver.
**Decisions**: All `"` inside `bash -c "..."` must be escaped `\"`. stateStore must be cleared when marker reconciliation downgrades a step. Env file deletion moved out of cleanup_previous().
**Next**: Install virpipe-capable Mesa in proot (TUR/custom build), verify GPU acceleration, production P1s.

### Session 38 (2026-02-24)
**Work**: Implemented Mali GPU acceleration plan via `/implement`. 5 phases (GPU detection, Mali setup, launch script tiered fallback, Kotlin changes, shutdown cleanup). 7 files (2 new + 5 modified). Code review found 7 P1s, all fixed and verified. 2 builds passed.
**Decisions**: VirGL server tied to session lifecycle, GL version override AFTER GL check (not before), GPU setup non-blocking, polling loops (2s max) for VirGL readiness, 512MB disk space pre-check, grep -Eo (POSIX) not grep -oP (PCRE).
**Next**: On-device test (especially Mali VirGL spike), commit changes.

### Session 37 (2026-02-24)
**Work**: 6-wave review-fix-verify loop with 12 review agents. 21 fixes across 17 files. Standard reviews (code/perf/security), verification, final check, then 3 production-scrutiny waves (edge cases, stress/resilience, adversarial security). 4 logical commits.
**Decisions**: Double-quote shell escaping (not single-quote), IMMUTABLE_FLAGS for notification PendingIntents, sentinel file for health monitoring (not PID+pgrep), corrupted EncryptedSharedPreferences auto-recovery.
**Next**: On-device test of full app. Consider production P1s (cert pinning, APK sig verify).

### Session 36 (2026-02-24)
**Work**: Implemented lifecycle + GPU plan via `/implement` (3 phases, 6 quality gates, 1 orchestrator cycle). 5 new files, 9 modified. GeckoView auth also committed. 4 logical commits.
**Decisions**: Companion object MutableStateFlow for service-to-UI comm, startForeground in handleCheckSession for restart safety, GPU step non-blocking, POST_NOTIFICATIONS soft-prompted.
**Next**: On-device test lifecycle, GPU, and session UI.

### Session 35 (2026-02-23)
**Work**: 3 parallel research agents (perf logs, lifecycle, GPU). Analyzed GC/CPU/memory/resolution from device logs. Identified GPU-on-llvmpipe as #1 bottleneck. User applied quick wins. Brainstormed combined lifecycle + GPU design (6 decisions, 7 sections). Design doc committed.
**Decisions**: Keep running on swipe, notification with Switch/Stop actions, automated GPU setup step, auto-fallback to software, auto-detect running session, 15s health poll, Foreground Service + shell scripts approach.
**Next**: Implement lifecycle (Phase 1), then GPU (Phase 2), then polish (Phase 3).

### Session 34 (2026-02-23)
**Work**: Implemented GeckoView auth (3 phases via /implement, 6 quality gates passed). Fixed cross-app file access bug (env file deployed via Termux stdin). Full auth flow verified on device. Added process cleanup/shutdown to launch script. Added perf logging (GC, CPU/mem monitor, GL info, --debug).
**Decisions**: Deploy env file via TermuxCommandRunner stdin (not app filesDir). Added JX_REFRESH_TOKEN to env file. pkill-based cleanup before each launch. EXIT trap for clean shutdown.
**Next**: Pull perf logs, tie app lifecycle together, GPU acceleration.

### Session 30 (2026-02-23)
**Work**: Implemented OAuth 2-step rewrite via `/implement` orchestrator (4 phases, 6 quality gates). 3 independent review agents. Fixed all findings: 1 P0 (JSON injection), 10 P1s, 3 completeness gaps.
**Decisions**: suspendCancellableCoroutine for OkHttp, CSRF token on forwarder HTML, specific exception types.
**Next**: On-device test of full login flow.

### Session 29 (2026-02-23)
**Work**: Brainstormed OAuth 2-step rewrite. 3 verification agents + 1 adversarial reviewer. 4 on-device tests (consent client standalone, jagex: scheme capture). Confirmed full 2-step flow required and jagex: scheme works. Designed complete implementation. 5 logical commits pushed.
**Decisions**: Full 2-step flow (not consent-client-only shortcut). jagex: intent scheme for Step 1. Forwarder HTML for Step 2 fragment capture. Game session API order reversed.
**Next**: Implement OAuth 2-step rewrite, test on device.

### Session 24 (2026-02-23)
**Work**: Fixed Cloudflare WebView block (remove `; wv` UA token). Brainstormed + implemented permissions automation (5 phases, 5 files, 6 quality gates, 4 P1s fixed).
**Decisions**: Copy-paste flow for Termux config (can't automate), auto-poll on resume, permissions before Termux work, strip `; wv` from WebView UA.
**Next**: On-device test of permissions + login, commit, then Slice 4+5.

### Session 23 (2026-02-23)
**Work**: Diagnosed and fixed OAuth login redirect failure. Chrome Custom Tabs callback unreliable — replaced with localhost server capture. Committed and pushed both OAuth fix + remaining unstaged changes.
**Decisions**: Localhost server for both auth steps (not just second). Removed CustomTabAuthCapture entirely.
**Next**: On-device test of OAuth fix, then implement Slice 4+5.

### Session 22 (2026-02-23)
**Work**: Full security review (3 parallel agents) + fix all 15 findings via `/implement`. 4 phases, 16 files, 6 quality gates passed. 5 commits pushed.
**Decisions**: sanitizeErrorBody() helper, in-memory access_token, loopback-only auth server, allowedHosts for onNewIntent, APK package verification via getPackageArchiveInfo, R8 + ProGuard for release builds.
**Next**: On-device test, then implement Slice 4+5.

### Session 1 (2026-02-21)
**Work**: Initial project setup. Created .claude directory with full state management system.
**Decisions**: Adopted Field Guide App's state management pattern.
**Next**: Define project scope, research RuneLite. (Completed in Session 2)

### Session 2 (2026-02-21)
**Work**: Deep research phase for PRD. 8 agents researched auth flow, architecture, existing projects, Android approaches, GPU options. All research saved to `.claude/research/`. Brainstorming in progress — architecture approved, presenting design sections.
**Decisions**: Installer app approach (Termux+proot+RuneLite.jar). Auth via credential import (MVP) then native OAuth2 (Phase 2). Software rendering first, Zink GPU later.
**Next**: Continue design presentation (UX flow, components, phasing), write design doc. (Completed in Session 3)

### Session 3 (2026-02-21)
**Work**: Completed brainstorming. Presented and approved all remaining design sections (UX Flow, Component Details, Phasing, Error Handling, Testing). Wrote and committed design doc. Cleaned stale writing-plans skill reference from brainstorming SKILL.md.
**Decisions**: All design sections approved as presented. Removed nonexistent writing-plans skill reference.
**Next**: Create implementation plan, scaffold Android project, begin MVP development. (Implementation plan completed in Session 4)

### Session 4 (2026-02-22)
**Work**: Brainstormed MVP implementation plan. Reviewed research docs for feasibility. Chose vertical slices approach (5 slices, 23 tasks). Designed technical architecture (Termux RUN_COMMAND, project structure, bundled shell scripts, key libraries). Wrote and committed implementation plan.
**Decisions**: Full MVP plan, skip manual PoC, vertical slices, RUN_COMMAND intent, shell scripts in APK assets.
**Next**: Begin Slice 1 — scaffold project, Termux integration, shell scripts. (Design completed in Session 5)

### Sessions 5-9 (2026-02-22)
**Work**: Slice 1 implementation + hardening. Scaffolded Android project, implemented all 15 Kotlin files + 2 shell scripts. 3-round review-fix-verify loop. System redesign approved. Logging system designed and committed.
**Key**: Full APK builds clean. Manual DI, single-screen, SetupActions pattern.

### Session 10 (2026-02-22)
**Work**: Implemented logging system (AppLog + CleanupManager). Code review + fixes. Build clean, 4 commits pushed.
**Next**: Debug first-run via ADB. (Sessions 11-15: on-device debugging)

### Session 11 (2026-02-22)
**Work**: First real on-device debug run. Fixed PackageInstaller confirm, runtime permission. Hit Termux result extras blocker.
**Decisions**: Runtime permission request at setup start. PackageInstaller STATUS_PENDING_USER_ACTION handling.
**Next**: Fix Termux result extraction. (Fixed in Session 12)

### Session 12 (2026-02-22)
**Work**: Fixed Termux Bundle extraction blocker. Steps 1-3 pass. Hit service lifecycle blocker.
**Decisions**: Extract results via `getBundleExtra("result")` not flat extras.
**Next**: Fix service lifecycle. (Fixed Session 13)

### Session 13 (2026-02-22)
**Work**: Fixed TermuxResultService lifecycle (onDestroy clearing static deferreds, stopSelfIfIdle). Fixed 5 shell script compatibility issues. Hit proot-distro install blocker.
**Decisions**: Removed deferred cancellation from onDestroy. Used `|| true` + verification checks.
**Next**: Fix proot-distro install exit code issue. (Fixed Session 14)

### Session 14 (2026-02-22)
**Work**: Fixed proot-distro install (manual rootfs extraction fallback). Fixed DNS, DEBIAN_FRONTEND, X11 socket. Hit headless-JDK blocker.
**Next**: Fix JDK + launch RuneLite. (Fixed Session 15)

### Session 15 (2026-02-22)
**Work**: RuneLite running on tablet! Fixed 3 launch blockers (full JDK, X11 socket, direct client launch).
**Next**: Display size + UX improvements. (Designed Session 17)

### Session 16 (2026-02-22)
**Work**: Designed Slice 2+3 plan. 5 parallel agents for research + adversarial review.
**Next**: Investigate display/launch UX defects. (Session 17)

### Session 17 (2026-02-22)
**Work**: Investigated display size + launch UX defects via ADB. Designed openbox WM + fullscreen + auto-switch approach.
**Next**: Redesign /implement skill. (Session 18)

### Session 18 (2026-02-23)
**Work**: Brainstormed and implemented redesign of `/implement` skill. New orchestrator agent pattern.
**Next**: Implement Slice 2+3 (completed in Session 19).

### Session 19 (2026-02-23)
**Work**: Implemented full Slice 2+3 via `/implement` skill. Ran 3 spikes via ADB. Completed all 15 phases. Fixed 8 P1s from code review.
**Decisions**: Option 2 for redirect (CustomTab callback), both OAuth2 paths, env file inside proot.
**Next**: Check gates 5+6. Commit everything.

### Session 20 (2026-02-23)
**Work**: Resumed `/implement` — re-ran Gates 5+6 (completeness + performance). Both passed. Committed all Slice 2+3 code (22 files) + tooling updates (6 files) in two commits.
**Decisions**: None (verification-only session).
**Next**: On-device test, push to remote.

### Session 28 (2026-02-23)
**Work**: Built and installed app on tablet. Custom Tabs auth opened Chrome successfully (Cloudflare passed). Jagex returned "Sorry, something went wrong" — server-side rejection. Researched 3 open-source launchers (aitoiaita, melxin, Bolt) via `gh api` to extract exact OAuth parameters. Discovered Jagex uses 2-step, 2-client-ID flow. Wrote comprehensive research doc.
**Decisions**: Must rewrite OAuth to correct 2-step flow. `jagex:` intent scheme for Step 1 capture.
**Next**: Rewrite OAuth flow, test on device, commit.

### Session 25 (2026-02-23)
**Work**: On-device test found 3 bugs: StepState NPE (lazy init fix), SecurityException in verifyPermissions (catch), isPermissionStepActive always false (MutableStateFlow). Fixed all 3. Iterated on Phase 1 UX — clipboard quotes corrupted, simplified command.
**Decisions**: Use `by lazy` for sealed class companion vals, MutableStateFlow for reactive boolean state.
**Next**: Finish on-device test, commit.

### Session 31 (2026-02-23)
**Work**: Built and tested on device. Found 3 bugs: (1) jagex: URI uses comma separators. (2) redirect_uri had port suffix. (3) Port 80 blocked on Android for ALL apps. Started intent filter approach.
**Decisions**: Intent filter for `http://localhost` is the right Android-native approach.
**Next**: Wire up intent filter handler, test on device.

### Session 32 (2026-02-23)
**Work**: Systematic debugging of port 80 blocker. Exhaustive investigation. Confirmed VpnService dead (loopback bypasses TUN). Tested proot port 80 (PermissionError). Built GeckoView PoC — passes Cloudflare, user logged in.
**Decisions**: GeckoView replaces Chrome Custom Tabs for auth. Both steps in GeckoView for cookie continuity.
**Next**: Integrate GeckoView into actual auth flow, verify fragment capture.

### Session 33 (2026-02-23)
**Work**: Brainstormed GeckoView auth integration design. Explored full auth codebase via agent (5 files, ~1327 lines). Made 6 design decisions. Presented 6 design sections, all approved. Wrote design doc.
**Decisions**: Dedicated GeckoAuthActivity, no Custom Tabs fallback, single launch both steps, Activity does token exchange internally, ActivityResult API, immediate cancel on back press.
**Next**: Implement design (4 phases), verify on device, then Slice 4+5.

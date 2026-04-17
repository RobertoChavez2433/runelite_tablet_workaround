# State Archive

Session history archive. See `.claude/autoload/_state.md` for current state (last 5 sessions).

---

## April 2026

### Session 71 (2026-04-16)
**Work**: H4/H5/H6 VirGL-profile tests all rejected. Re-framed Session 70's conclusion — "44-51 FPS ceiling" was login-screen damage rate, not in-game scene FPS. Ground-truth in-game via FpsPlugin overlay: ~10 FPS. Built observability bridge: `RLClient` logcat tag → DebugLogServer source=runelite. Added opt-in FPS probe (`RLT_DEBUG_FPS_PROBE=1`). Audited `spike/direct-android-surface` state; iteration log had a sitting next-step (dladdr resolution) that was never actioned.
**Decisions**: Resume direct-surface spike. Published per-slice plan (S0-S6) to reach 120 FPS. Stock VirGL+X11 path stays runnable as fallback. ANGLE variants permanently rejected.
**Next**: Session 72 starts at Slice 1 of `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`.

### Session 70 (2026-04-16)
**Work**: Systematic FPS ceiling investigation. Tested 3 hypotheses: Present flip (REJECTED — 0 attempts), VirGL threading (REJECTED — no FPS change), VirGL structural ceiling (CONFIRMED — 17-28ms inter-frame). Verified P1 attach loop fixed (1 attempt), P1 session health fixed (debounce working), P2 triggerCallback confirmed (43ms, startup only). Added TERMUX_X11_FORCE_FLIP=1 and VirGL threading-always-on to launch script (harmless, well-documented). Saved full findings to docs/fps-ceiling-research-session70.md.
**Decisions**: VirGL is the structural ceiling. No compositor-side fix possible. Next steps: Venus protocol, no-loop-or-fork test, llvmpipe baseline, or accept 40-50 FPS.
**Next**: Decide approach for VirGL throughput (Venus? Accept ceiling? llvmpipe comparison?).

### Session 69 (2026-04-14)
**Work**: Device verification on Samsung Tab S10 Ultra. Built + deployed debug APK. Full end-to-end: boot → setup (29.7s) → auth (token expired → GeckoView → 2-step OAuth → valid) → launch (health check → env deploy → hybrid_x11) → rendering (120 FPS Kotlin, 42-54 FPS native). All logging layers verified: DI, setup, auth, correlation IDs (3 levels), surface lifecycle, binder bridge, fd tracking, buffer balance, native init/shaders/mmap, DebugLogServer HTML+WebSocket, session health. Saved device logs to docs/logs/.
**Decisions**: Attach loop needs a connected-state guard (too chatty). Session health first-poll timing needs work. Frame timing reporting is accurate.
**Next**: Fix native FPS ceiling (VirGL readback bottleneck).

### Session 68 (2026-04-14)
**Work**: Three spec audit rounds closing ALL gaps. 127/128 spec items PASS. VirGL watchdog, FdTracker, deep correlation threading, ApkDownloaderTest. 11 layer-organized commits. 210 tests.
**Decisions**: FdTracker as singleton. Logger injection with AppLog default. VirGL background watchdog.
**Next**: Device verification (completed in session 69).

### Session 67 (2026-04-14)
**Work**: Full Phase 9 implementation. DebugLogServer, Logger 11 new methods, 30+ files instrumented, native C instrumented. 205 tests.

### Session 66 (2026-04-14)
**Work**: Spec audit (9/10 PASS). Logging audit (53/82 files unlogged). Wrote Phase 9 spec.

## March 2026

### Session 61 (2026-03-16)
**Work**: Deep presentation pipeline analysis. Identified VirGL readback bottleneck, Xlorie RGBA format issue, waitForNextFrame 2-vsync cap.
**Decisions**: 4-phase approach for 120fps. Blocked on architecture refactor.
**Next**: Begin Phase 1 implementation (blocked on refactor — unblocked by Session 64).

### Session 59 (2026-03-15)
**Work**: Added native renderer timing and X11-side cadence counters, extended the real RuneLite evidence harness, forced RuneLite GPU settings (`unlockFps=true`, `fpsTarget=120`, `vsyncMode=OFF`), and captured internal-hybrid default-res, half-res, and `--scale 1` evidence runs. Also wired the combined probe harness so it can drive `internal-hybrid` synthetic controls, then captured the first fullscreen `glxgears` control attempt and invalidated it after confirming install and probe overlapped and Android force-stopped the app during install.
**Decisions**: The Android-present step is no longer the main suspect; the remaining ceiling is upstream of it. Future synthetic `internal-hybrid` control runs must be strictly sequential after deploy.
**Next**: Rerun the internal-hybrid VirGL fullscreen `glxgears` control sequentially, then continue upstream client-path diagnostics.

### Session 58 (2026-03-15)
**Work**: Debugged the in-app Java-side X server experiment until `internal-hybrid` successfully booted real RuneLite. Added startup-log preservation in `launch-runelite.sh`, widened diagnosis with raw logcat, proved donor `libXlorie.so` must be extracted from the installed `com.termux.x11` APK rather than loaded from `nativeLibraryDir`, fixed the extraction target so it writes into the Termux runtime filesystem instead of `ctx.getFilesDir()`, rebuilt and installed the app, and captured the first successful clean-start `internal-hybrid` real RuneLite runs at default-res and half-res.
**Decisions**: The in-app bootstrap route is now a real measurement path, not just a spike. But the first working captures still land in essentially the same sustained FPS envelope as the external hybrid path, so bootstrap ownership alone is not the missing unlock.
**Next**: Compare `internal-hybrid` against the external hybrid path as a runtime architecture question and then pivot toward deeper presentation-path changes if the envelope still does not move.

### Session 57 (2026-03-15)
**Work**: Started the first in-app Java-side X server experiment. Added an in-app `com.termux.x11.CmdEntryPoint`, launched it via `app_process` from our APK, introduced an `internal-hybrid` presentation variant in `launch-runelite.sh`, updated `HybridX11TestReceiver` and `scripts/hybrid-x11-runelite-evidence.ps1` to drive that variant, and built/installed the app. Captured the first clean-start `internal-hybrid` real RuneLite run. The run failed before X11 socket creation and never reached client initialization.
**Decisions**: The stock/hybrid X11 comparator remains the baseline, not the answer. The app-owned server route is still the right direction, but the next blocker is earlier than rendering or pacing: the in-app `CmdEntryPoint` path must create the X11 socket and stay alive before it can be measured against RuneLite.
**Next**: Capture direct `app_process` stdout/stderr for the in-app `CmdEntryPoint` launch, fix the earliest boot failure until the X11 socket appears, and only then re-run real RuneLite on `internal-hybrid`.

### Session 56 (2026-03-15)
**Work**: Extended the real RuneLite evidence path into a true stock-vs-hybrid comparator on the shared launcher path. Patched `launch-runelite.sh` and `HybridX11TestReceiver` so the real launcher can run either `stock` or `hybrid`, updated `scripts/hybrid-x11-runelite-evidence.ps1` to emit variant-aware visible-surface stats plus average/median FPS summaries, and captured matching 30-second clean-start real RuneLite runs for both variants. Then added and validated a forced half-res GPU display override (`1480x924`) on the hybrid path.
**Decisions**: Stock and hybrid perform in essentially the same sustained FPS band on the current X11/VirGL path, so the remaining ceiling is not credibly hybrid-only. Lowering GPU display resolution to `1480x924` does not materially improve the sustained envelope, so the current X11/VirGL route does not look viable for a true sustained `120 FPS` target without a deeper redesign.
**Next**: Pivot away from parity-only testing and toward a path that can plausibly beat the current X11/VirGL ceiling, either via a deeper presentation-path redesign or a more aggressive app-owned/direct-surface route.

### Session 55 (2026-03-15)
**Work**: Added clean-start host-side harnesses for synthetic X11 probes and real RuneLite evidence. Validated clean-start stock/hybrid synthetic probes, corrected an invalid parallel probe attempt, and captured the first clean-start real RuneLite evidence run on the hybrid host. Verified real RuneLite reaches `running`, the GPU plugin is active on `virgl`, the visible hybrid surface is voted `120.00 Hz`, and sustained rendering is still mostly around the `50-65 FPS` range rather than `120 FPS`.
**Decisions**: The hybrid RuneLite evidence harness is now the default measurement method for top-level performance claims. Synthetic probes remain useful for quick isolation, but they no longer define success or failure by themselves.
**Next**: Use the real RuneLite evidence harness to drive the next optimization pass on the X11/VirGL presentation chain, or build a like-for-like stock real RuneLite comparator if needed.

### Session 54 (2026-03-15)
**Work**: Created and advanced the `spike/direct-android-surface` branch. Added a live hybrid iteration log, validated direct app-owned surface `120 fps`, proved `TERMUX_X11_OVERRIDE_PACKAGE` routing into our app, built a functioning hybrid host activity that launches real RuneLite with native VirGL, restored safe trackpad-style touch semantics, and ran multiple stock vs hybrid VirGL probes. Latest result before the clean-start harness work: hybrid split-start probes still collapse around `0.4 FPS`.
**Decisions**: Stay on the existing Linux RuneLite path. Use hybrid option `C` first. Touchscreen must remain trackpad-style only; no direct-touch injection. Real hardware mouse/touchpad stays real pointer input.
**Next**: Replace ad hoc probe runs with clean-start harnesses, then measure real RuneLite instead of relying only on synthetic clients.

### Session 53 (2026-03-12)
**Work**: Implemented VirGL fix pipeline via `/implement` (5/7 phases, 6 quality gates PASS). Modified 4 files: `gl_test_log.h`, `gl_test_harness.c`, `run-tests.sh`, `launch-runelite.sh`. Deployed to device via `device-run.sh`. On-device: SIGSEGV persists and all rendering is black. Phase 0+1+2 fixes were insufficient.
**Decisions**: `device-run.sh` for adb deployment to bypass Git Bash quoting issues. FBO creation ordering fix. `mali-native` uses GLSL 140, `mali-angle` uses GLSL 430. Use `tr -d "\\015"` for CR stripping instead of `sed`.
**Next**: Debug SIGSEGV by running modules individually, debug black rendering with a trivial triangle path, and run the FBO probe (`--module 7`).

### Session 52 (2026-03-11)
**Work**: Root-cause analysis via 3 parallel research agents. Plan written by a Sonnet agent. Adversarial review by Opus found 7 MUST-FIX and 9 SHOULD-CONSIDER issues, all incorporated into the approved 6-phase plan. No code changes in this session.
**Decisions**: `GL_DEPTH_CLAMP` is the highest-probability FBO fix. Use `4.3COMPAT+430` for version consistency. Prefer a renderbuffer over a depth texture. `GLFW_VISIBLE=TRUE` is required. Phase 2 should deploy as a single unit.
**Next**: Implement Phase 0 (SIGSEGV fix), Phase 1 (env fixes), and Phase 2 (FBO code fixes), then deploy and test.

### Session 51 (2026-03-10)
**Work**: Committed the test pipeline (14 files) plus an `LD_LIBRARY_PATH` fix. Systematic debugging fixed `GL_MAX_VIEWPORT_DIMS` overflow, `DEPTH_COMPONENT32F` to `DEPTH_COMPONENT24`, and added UBO diagnostic logging. Deployed, rebuilt, and ran on device. Critical finding: FBO rendering is completely broken on VirGL, and SIGSEGV persisted despite the viewport-dims fix.
**Decisions**: Use `DEPTH_COMPONENT24` over `32F`. Use `MSYS_NO_PATHCONV=1` for adb from Git Bash. Push individual files rather than directories. Invoke scripts via a full bash path rather than shebangs.
**Next**: Test rendering to the default framebuffer, isolate SIGSEGV by running module 1 individually, and redesign tests if FBO is fundamentally broken on VirGL.

### Session 50 (2026-03-10)
**Work**: Implemented the VirGL test pipeline via `/implement` (Phases 1-6, 13 files, 6 quality gates PASS). Fixed 4 P2 review nitpicks, deployed to device, and resolved 3 deployment bugs (`LD_LIBRARY_PATH`, X11 locks, `TMPDIR`). First successful on-device run showed VirGL+ANGLE works and both shims activate correctly, but all depth tests rendered black.
**Decisions**: Use `env -u LD_LIBRARY_PATH` for the VirGL server because of Termux OpenSSL conflicts. Always kill and clean X11 before starting to avoid stale locks. Set `TMPDIR` and `XDG_RUNTIME_DIR` in self-bootstrap. Set `GLFW_PLATFORM=x11` for GLFW 3.4.
**Next**: Debug the black rendering path, fix Modules 1-3 SIGSEGV, apply the `LD_LIBRARY_PATH` fix to `launch-runelite.sh`, and commit the files.

### Session 48 (2026-03-09)
**Work**: VirGL socket fix achieved via 4-agent research. 11 changes: `--shared-tmp`, socket wait with `[ -S ]`, `MESA_GLX_ALPHA_BITS=0` (24-bit visual fix), glxgears instead of glxinfo, stock Mesa replaces lfdevs, `termux-x11-preference` replaces xrandr (colon syntax), `com.termux.x11.Loader` cleanup pattern. VirGL confirmed working (virgl renderer detected). GPU plugin fails: GLSL 3.30 not supported.
**Decisions**: Stock Ubuntu Mesa for Mali (not lfdevs). Termux:X11 preferences for resolution (not xrandr). glxgears for virpipe detection (not glxinfo). MESA_GLX_ALPHA_BITS=0 for visual depth fix.
**Next**: Add MESA_GLSL_VERSION_OVERRIDE=330 (one-line). Test GPU plugin. Software fallback resolution.

### Session 47 (2026-03-09)
**Work**: Eliminated hardcoded versions (launcher 2.7.7, Mesa 26.1.0). Fixed RuneLite always-run-launcher (auto-update). Fixed JVM flag propagation (--scale 2, RUNELITE_VMARGS=-Xmx4g). Fixed lfdevs Mesa download URL (was 404). Fixed GPU marker staleness. Deployed v2→v5 across 4 iterations. lfdevs Mesa 26.1.0-devel now confirmed installed. VirGL still broken — socket path mismatch.
**Decisions**: Use RUNELITE_VMARGS (not JDK_JAVA_OPTIONS) for client JVM args. Use --scale 2 (launcher's native mechanism). Always delete GPU marker on setup-gpu run. Fallback Mesa version = mesa-26.1.0-devel-20260208 (actual existing tag).
**Next**: Fix VirGL socket path (P0). Lower xrandr resolution as interim perf mitigation. Test auth in-game.

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

## March 2026

### Session 60 (2026-03-15)
**Work**: Added a working `internal-hybrid` `direct-jvm` real RuneLite launch mode and wired it through the real evidence harness. Fixed two harness issues on the way there: appended Termux `runelite-launch.log` history was causing false-positive readiness, and seeded direct classpaths were being corrupted by Windows CRLF. Validated fresh launcher-vs-direct evidence runs, added a classpath-override directory for patched jars, pulled the live RuneLite jars from the device, and saved a bytecode dump of `GpuPlugin` to confirm the concrete next patch target in `prepareInterfaceTexture(...)` / `drawUi(...)`.
**Decisions**: Bypassing the RuneLite launcher bootstrap is not the missing unlock. Keep `direct-jvm` as the patch/testing vehicle and move the next work to targeted client-side jar experiments instead of more launcher/X11 ownership changes.
**Next**: Patch the RuneLite GPU client path, starting with the interface upload / synchronization path in `client-1.12.20.jar`, then measure that patched jar through the new override path.

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

### Session 49 (2026-03-09)
**Work**: Investigated GPU plugin black screen via 8 research agents. Root cause: reversed-Z depth buffer requires `glClipControl` (GL 4.5) which VirGL doesn't support. `GL_ARB_clip_control` confirmed absent from extension list. `MESA_NO_ERROR=1` masked the failure. Applied GLSL 330 override (shaders compile) and GL 4.5 override (didn't fix — LWJGL checks function pointers not just version string). Captured full VirGL capability dump. Designed 3-tier test pipeline via `/brainstorming` -> `/adversarial-review` -> `/writing-plans`. 6 MUST-FIX items found and addressed.
**Decisions**: LD_PRELOAD shim approach (not patching RuneLite). Standalone developer tool (not app integration). Sub-process LD_PRELOAD testing (not dlopen). Cross-UID deploy via staging to /data/local/tmp/. Environment allowlist for results (not dump-all).
**Next**: `/implement` test pipeline (7 phases). Deploy, run --quick, determine winning shim. Apply to launch-runelite.sh.

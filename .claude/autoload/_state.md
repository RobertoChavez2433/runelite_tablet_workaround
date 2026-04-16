# Session State

**Last Updated**: 2026-04-16 | **Session**: 73 (end — convention migration)

## Current Phase
- **Phase**: `spike/direct-android-surface` — Slices 1-3 landed (FPS gain). Session 73 layered a commit/issue convention + GitHub-issue defect migration on top.
- **Status**: EXA software fallback eliminated (session 72: `lorieUploadToScreen`, 0% → 99.6% accel, damage-redraws 44-51 → 60-66 FPS). Defect tracking moved off `.claude/defects/*` onto GitHub issues per `.claude/specs/2026-04-16-issue-convention-spec.md`; commit grammar enforced by `.claude/specs/2026-04-16-commit-convention-spec.md` + `scripts/git/commit-msg` hook.

## HOT CONTEXT — Resume Here

### ENTRY POINT FOR SESSION 74

**FPS work**: start at Slice 4 of `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`. Recommended first move is the "sticky AHB lock" in `lorieUploadToScreen` (leave buffer locked across consecutive PutImage calls; compositor's `loriePrepareAccess` already handles already-locked). Expected 2-3x FPS. Then measure in-game via `RLT_DEBUG_FPS_PROBE=1` + user-triggered login. If still under 120, start Slice 5 (direct SurfaceView AHB attach).

**Convention now enforced**: every commit must be `type(scope): subject` + narrative body + `Reason:` trailer (see `scripts/git/commit-msg`). File defects via `tools/create-issue.ps1` — do NOT write `.claude/defects/*`.

**Open issues carried forward**:
- `#6` — P1, `security(auth)`: shellEscape missing `!` and does not strip CR/NL/NUL.
- `#7` — P2, `needs-repro`: UnsafeHelper abstract-class allocation.
- `#25` — P2, `needs-repro`: MESA_GL_VERSION_OVERRIDE does not unlock LWJGL OpenGL45 (LD_PRELOAD shim never shipped).
- `#43` — P2, `needs-repro`: SetupOrchestrator isSuccess marker.

### What Session 72 Shipped

- **Slice 1 complete**: extended `DamageTraceV2` in `damage.c` with dladdr offset
  logging + routed through `__android_log_print` (was `ErrorF` which doesn't reach
  logcat). Resolved hot path: `exaPutImage` @ `exa_accel.c:252`, hot Composite:
  `exaComposite` @ `exa_render.c:887`. Both 100% in EXA framework. See
  `docs/logs/slice1-putimage-symbols.txt`.
- **Slice 2 complete**: damage attribution. 99.5% of PutImage events are 2898×22
  horizontal strips reconstructing the RuneLite client window (84 strips per
  frame). See `docs/logs/slice2-damage-attribution.md`.
- **Slice 3 complete (landed, partial target)**: implemented `lorieUploadToScreen`
  in `InitOutput.c` — directly writes pixel data into AHARDWAREBUFFER-backed pixmaps.
  Before: 0 accel / all fallback. After: 99.6% accel (1837 accel / 8 fallback in
  150s). Damage-redraw rate 44-51 → 60-66 FPS (+25-30%). Below plan target of 2.5×
  because compositor polling rate (bounded by vsync) masks per-call improvement;
  actual scene FPS gain not measured (requires interactive RuneLite login).
- **Side fix landed**: `ScriptManager.scriptsDeployed` promoted to companion-object
  so broadcast-receiver-created ScriptManager instances share the activity
  setup-flow's deployment state. Without this, every launcher broadcast tried to
  re-deploy all 18 scripts and timed out at 30s due to Termux BAL-denied service
  start (Android 14+ FGS rules).

### Session 71 shipped (still relevant)

- `launch-runelite.sh`: sentinel-file VirGL-profile override (opt-in via `$HOME/.rlt-virgl-profile-override`); RuneLite client.log tail → logcat tag `RLClient`; **opt-in** FPS probe gated by `RLT_DEBUG_FPS_PROBE=1` (default off, restores profile from `.rlt-orig` backup on launch).
- `DebugLogServer.kt`: subscribes to `RLClient:V` logcat tag, routes `RL*` tags as `source="runelite"`, HTML viewer has new `runelite` filter pill.
- H4/H5/H6 rejections documented in `runelite-tablet/docs/fps-ceiling-research-session71.md`.
- Session-end findings + direction in `runelite-tablet/docs/session-71-findings-and-direction.md`.
- Per-slice plan (S0-S6) in `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`.

### Device State at Session 71 End

- APK: built + installed on R52X90378YB with all session-71 changes.
- `com.termux.permission.RUN_COMMAND`: granted.
- VirGL sentinel: absent (default native-gles).
- FPS probe: disabled (profile restored from backup on next launch).
- Stock launch path works normally.

### Key Reframing

- **`damage-triggered redraws FPS` is NOT scene FPS** when any per-frame overlay is active. It counts overlay redraws too.
- **FpsPlugin overlay is the ground-truth** for user-visible FPS. Turn on via `RLT_DEBUG_FPS_PROBE=1`.
- **Direct SurfaceView probe already hit 120 FPS** on this tablet. Hardware is not the limit. The limit is presentation chain.
- **`present after-flips = 0`** in all captures — Present extension is entirely unused.

### Side Findings (Parked)

- `EnablePermissions` setup step marks Done without checking Android permission. Fix is trivial (~5 lines Kotlin) but not on the critical path.
- Black-line-at-top cosmetic artifact — deferred per user direction.
- Both side findings are in the plan's "Deferred" section.

## Blockers

**1. Per-PutImage overhead still dominates** (session 72: damage-rate vsync-capped around 60 FPS).
- EXA accel wired up (session 72), but RuneLite still issues ~1500 XPutImage/sec across 84 strips per frame.
- Next lever: sticky AHB lock (skip lock/unlock between consecutive strips).
- Backup lever: Slice 5 direct SurfaceView AHB attach, but no scaffolding exists beyond `DirectSurfaceProbeActivity` (which just renders colored Canvas bands).

**Stale blockers removed (Session 72):**
- ~~Xlorie EXA hot path unknown~~ — Resolved: `exaPutImage` / `exaComposite` named via dladdr + addr2line (session 72 Slice 1).
- ~~VirGL vtest socket serialization is the structural FPS ceiling~~ — Refuted: bottleneck was 100% EXA software fallback, not VirGL throughput. VirGL is producing frames fine; the X server was just CPU-copying them via fbPutImage.

**Stale blockers removed (Session 70):**
- ~~Xlorie legacy drawing active on Mali due to wrong format~~ — Device confirms `legacy_drawing=0`, BGRA AHARDWAREBUFFER path active.
- ~~`waitForNextFrame` 2-vsync cap~~ — Dead code since Phase 2B, never set to true.

## Recent Sessions

### Session 73 (2026-04-16)
**Work**: Ported Field Guide's commit convention + authored a parallel issue convention. Installed `scripts/git/commit-msg`, `scripts/git/valid-scopes.txt`, `.gitmessage`, `tools/create-issue.ps1`, and `tools/migrate-defects-to-issues.sh`. Three parallel audit agents classified all 56 local defects (1 OPEN, 51 RESOLVED, 2 STALE, 3 UNKNOWN). Created 54 GitHub issues on `RobertoChavez2433/tablite` (50 closed as historical, 4 open: #6 security + #7/#25/#43 needs-repro). Deleted `.claude/defects/` + archive; updated `CLAUDE.md` and `end-session` skill.
**Decisions**: Defects are GitHub-only going forward. Commit hook is enforced on every commit. `type(scope): subject` + narrative body + `Reason:` trailer is mandatory for `feat`/`fix`/`refactor`/`perf` and for scoped lightweight commits.
**Next**: Session 74 returns to FPS work (sticky AHB lock) + clears the 3 needs-repro issues.

### Session 72 (2026-04-16)
**Work**: Executed Slice 1-3 of direct-surface plan on R52X90378YB. Extended `DamageTraceV2` with dladdr offset + rerouted to `__android_log_print` (ErrorF isn't captured in this build). Named hot path: `exaPutImage` @ `exa_accel.c:252`, `exaComposite` @ `exa_render.c:887`. Discovered `lorieExa` has no `UploadToScreen` → 100% software fallback. Implemented `lorieUploadToScreen` (direct AHB writes) → 99.6% accel. Damage-redraw rate 44-51 → 60-66 FPS. Also fixed `ScriptManager` re-deploy timeout (promoted cache flag to companion-object so broadcast-created instances share state).
**Decisions**: 120 FPS gate not hit; ~25% improvement over session 71 baseline. Prior "VirGL ceiling" framing was wrong — root cause was software PutImage, not VirGL. Sticky-lock in `UploadToScreen` is the next cheap lever (~20 LoC, expected 2-3×).
**Next**: Session 73 adds sticky AHB lock + measures in-game FPS via RuneLite FpsPlugin overlay (needs interactive login). If still under 120, pivot to Slice 5 (direct SurfaceView AHB attach).

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

## Active Plans

- **Phase 9: Comprehensive Logging System** — **COMPLETE + DEVICE-VERIFIED**. 127/128 spec items. 210 tests. All layers verified on device.
- **Clean Architecture Refactor (Phases 1-8)** — **COMPLETE**.
- **Presentation Pipeline 120 FPS** — **IN PROGRESS** (`.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`). Slices 1-3 complete. Slice 3 landed `lorieUploadToScreen`; damage-redraw rate 44-51 → 60-66 FPS. Slice 4 (DRI3 Present) deferred as low-yield — PutImage is the hot path, not Present. Next: sticky AHB lock, then Slice 5 (direct SurfaceView). Prior "VirGL is the ceiling" framing (`docs/fps-ceiling-research-session70.md`) is refuted by session 72 evidence.

## Reference
- **Source code**: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- **Test code**: `runelite-tablet/app/src/test/java/com/runelitetablet/`
- **Native code**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/`
- **Debug docs**: `runelite-tablet/docs/debug-logging.md`
- **FPS research (session 70)**: `runelite-tablet/docs/fps-ceiling-research-session70.md`
- **Device logs (session 69)**: `runelite-tablet/docs/logs/2026-04-14-device-verification-rlt.log` (10K lines), `*-native.log` (1.4K lines), `*-full.log` (149K lines)

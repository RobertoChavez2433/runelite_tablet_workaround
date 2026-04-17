# Session 79 TODO — Fix Termux Mesa ↔ Xlorie GLX handshake + close window-size loop

**Date created**: 2026-04-17 (end of S78)
**Parent plan**: `.claude/plans/2026-04-17-rlawt-bionic-rebuild.md` §Phase 3
**Preceded by**: Task 21 ✓, Phase 2.1 ✓, Phase 2.2 ✓ Kotlin-wired, Phase 3 R1+R2 partial (RL renders, GPU plugin fails, window small).
**Purpose**: a checkbox-style plan for the next session. Research + logging extensions first, then testing, then implementation. Evidence before code. No guessing.

**Root cause isolated at end of S78** (see `docs/logs/phase-2.2-diag-preflight-evidence.log`):
- Termux Mesa client returns zero FBConfigs against Termux:X11 (X server).
- Ubuntu Mesa inside proot-distro returns FBConfigs against the same X server.
- Termux Mesa 26.0.5 vs Ubuntu Mesa build difference is the delta.
- Same error observed from `glxinfo`, `glxgears`, and `rlawt`'s `glXChooseFBConfig` — this is NOT a rlawt bug, NOT a RuneLite bug, NOT a LaunchCoordinator bug.
- All permutations tested failed the same way: default env, `LIBGL_ALWAYS_SOFTWARE=1`, `LIBGL_ALWAYS_INDIRECT=1`, explicit `LIBGL_DRIVERS_PATH`, `MESA_EXTENSION_OVERRIDE=`.

---

## Section A — Logging extensions BEFORE any fix

Close the remaining diagnostic gaps so we can verify fixes with evidence, not guesses.

- [ ] **A1. Log GLX protocol-level query from Termux Mesa**
  - Goal: see what Mesa sends to the X server and what the server returns during FBConfig enumeration.
  - Approach: run `strace -e trace=connect,sendto,recvfrom -f -s 256 glxinfo -B` under Termux and dump into the DIAG log. Compare to the same strace inside proot-Ubuntu — the place the protocols diverge is the bug surface.
  - EVIDENCE: `runelite-tablet/docs/logs/phase-3-glx-strace-diff.log`.

- [ ] **A2. Log X server extension advertisement as seen from Termux libX11**
  - Goal: enumerate what GLX extensions Termux:X11 advertises when queried from Termux-native (vs proot).
  - Approach: write a ~30-line C probe that calls `XQueryExtension("GLX", …)` + `glXQueryVersion` + `glXQueryExtensionsString` + `glXGetClientString(GLX_EXTENSIONS)`. Compile with NDK, ship via APK assets, run from DIAG.
  - EVIDENCE: DIAG block prints the extension lists side by side.

- [ ] **A3. Log Mesa's swrast/llvmpipe loader attempts**
  - Goal: prove whether Termux Mesa is even trying to load `swrast_dri.so` / `llvmpipe_dri.so` (it may skip them because it thinks the X server doesn't have the right extensions).
  - Approach: `strace -e trace=openat -f glxinfo 2>&1 | grep -E 'dri.so|llvmpipe'`. Include in DIAG.
  - EVIDENCE: DIAG strace-openat output.

- [ ] **A4. Log Xlorie's GLX advertisement source**
  - Goal: find the Xlorie-side code that decides which GLX extensions to advertise. Dump that at Termux:X11 startup too.
  - Approach: grep `third_party/termux-x11-upstream/` for `glXQueryExtensions`, `glx_init`, `GLX_EXTENSIONS`, `AIGLX`. Add logging to the Xlorie C code and rebuild the com.termux.x11 APK.
  - EVIDENCE: Xlorie startup log showing which GLX init path was taken.

- [ ] **A5. Log RL's AWT frame bounds decisions on both paths**
  - Goal: quantify the "window small" problem — captures `Graphics device ... bounds` + `ContainableFrame ... size` + frame-move events in one place across both proot and native paths.
  - Approach: simple — grep the two `runelite-*.log` files side by side, extract those three lines, dump into a doc.
  - EVIDENCE: `runelite-tablet/docs/logs/phase-3-frame-bounds-proot-vs-native.md`.

---

## Section B — Research BEFORE any fix

No code changes in this section. Understand the problem fully.

- [ ] **B1. Diff Ubuntu Mesa vs Termux Mesa build configurations**
  - Goal: identify the build-flag or backend difference that makes Ubuntu Mesa work past FBConfig.
  - Approach:
    - `apt-cache show libgl1-mesa-dri` inside proot to get Ubuntu Mesa version + package source.
    - `dpkg -L libgl1-mesa-dri | grep -E '(so|json)'` to list shipped artifacts.
    - `pkg show mesa` under Termux to get the Termux Mesa package + its build deps.
    - Compare presence of: `gbm.so`, `libEGL_mesa.so`, `zink_dri.so`, glvnd dispatcher vs legacy libGL, the `gallium-drivers` build list.
  - EVIDENCE: a short diff doc: `runelite-tablet/docs/mesa-termux-vs-ubuntu-diff.md`.

- [ ] **B2. Research Termux:X11 (Xlorie) GLX backend**
  - Goal: understand how Xlorie implements GLX. Is it native AIGLX? EGL-backed? GLX passthrough to Android SurfaceFlinger?
  - Approach: read `third_party/termux-x11-upstream/app/src/main/cpp/lorie/` for GLX init code. Read the Xlorie README + its upstream Xserver changes. Identify what client-side Mesa API Xlorie expects.
  - EVIDENCE: a short explainer doc: `runelite-tablet/docs/xlorie-glx-arch.md`.

- [ ] **B3. Research the "AI-GLX / DRI2 vs DRI3" inflection**
  - Goal: Ubuntu Mesa likely falls back to DRI2 (software swrast) when DRI3 isn't advertised. Termux Mesa may skip straight to DRI3 and give up. Confirm/refute.
  - Approach: `LIBGL_DRI3_DISABLE=1 glxinfo -B` under Termux (S78 already tried a few envs, not this one — need to confirm). If that succeeds on Termux, the fix is a launcher env flag.
  - EVIDENCE: one-line result in DIAG log.

- [ ] **B4. Research workarounds used by other Termux:X11 users**
  - Goal: someone in the termux-packages issues has surely hit this. Find what they did.
  - Approach: search https://github.com/termux/termux-packages for "glXChooseFBConfig" / "couldn't find RGB GLX visual" / "glxgears mesa". Record any PRs or workaround env flags.
  - EVIDENCE: link + excerpt in session-79 notes.

---

## Section C — Testing after we have a hypothesis

For each hypothesis from B, one A/B test that either confirms or refutes it. One test per hypothesis — no "try everything at once."

- [ ] **C1. Test: does `LIBGL_DRI3_DISABLE=1` unblock Termux Mesa?**
  - Exit: `glxinfo -B` returns non-empty FBConfig list OR confirms failure continues.
  - Next on pass: wire env into launcher.
  - Next on fail: move to C2.

- [ ] **C2. Test: does `GALLIUM_DRIVER=llvmpipe` (force software) + `LIBGL_ALWAYS_SOFTWARE=1` help?**
  - Exit: same shape as C1.

- [ ] **C3. Test: does setting `VDPAU_DRIVER=none MESA_LOADER_DRIVER_OVERRIDE=llvmpipe` help?**
  - Exit: same shape.

- [ ] **C4. Test: bundle Ubuntu Mesa-derived libGL as LD_PRELOAD override**
  - Goal: the nuclear option. Pull `libGL.so`, `libGLX.so`, `libGLX_mesa.so`, `swrast_dri.so` from proot-Ubuntu; stuff them under `$HOME/.rlt/mesa-override/` and set `LD_LIBRARY_PATH` to prefer them.
  - Exit: `glxinfo -B` from Termux context finds FBConfigs.
  - Deferred if C1–C3 pass.

- [ ] **C5. Test: does window-size improve once GPU plugin turns on?**
  - Dependency: any of C1–C4 passed.
  - Exit: screenshot + log of AWT frame size with GPU plugin running. Expected: RL stretched-mode fills the canvas.

---

## Section D — Implementation (only after a test passes)

- [ ] **D1. Ship the confirmed workaround in `launch-runelite-native.sh`**
  - Either: add env exports from the passing C-test.
  - Or: ship vendored Mesa libraries (C4).
  - Or: patch Termux:X11 Xlorie (B2) and rebuild the com.termux.x11 APK.

- [ ] **D2. Update Phase 2.2/3 spec Review Notes with the confirmed fix**
  - `.claude/specs/2026-04-17-phase-2.2-and-3-spec.md` R3 → closed.

- [ ] **D3. Measure FPS A/B at Varrock East Bank**
  - Baseline (`useNativeTermux=false`): expected ~12 FPS.
  - Native (`useNativeTermux=true`): target ≥30 FPS (spike goal ≥60, S-FINAL ≥100).
  - Capture: two 60-second FpsPlugin logs in `docs/logs/phase-3-fps-*.log`.

- [ ] **D4. Remove the `scripts/native-launcher-wrapper.sh` ad-hoc test shim**
  - Now redundant since Phase 2.2 Kotlin dispatch supersedes it.

- [ ] **D5. Wire `useNativeTermux` into SettingsScreen**
  - User can toggle without adb XML surgery.

---

## Section E — Orthogonal window-size fix (parallelizable)

Even if GPU plugin stays broken, make the native path LOOK right so we have a clean UI while Mesa gets unblocked.

- [ ] **E1. Write RL profile's `runelite.gameSize` at native launch based on screen size**
  - Adaptive: compute target gameSize from `context.resources.displayMetrics` / uiScale (same derivation as RLT_UI_SCALE).
  - Write into `$ROOTFS_PATH/root/.runelite/profiles2/default-*.properties` before RL launch, similar to proot launcher's settings patching.
  - Exit: AWT Frame fills the X canvas.

- [ ] **E2. Enable RL stretched mode for native path**
  - Set `stretchedmode.scalingFactor=100`, `stretchedmode.keepAspectRatio=true`, `runelite.automaticResizeType=RESIZE_WINDOW` in profile.
  - Exit: game canvas stretches to fill the RL frame.

---

## Exit Criteria for Session 79

- [ ] All Section A items closed → we can debug Mesa issues without flailing.
- [ ] ≥ 2 Section B hypotheses researched → concrete theories before testing.
- [ ] ≥ 1 Section C test passes OR all fail with specific-evidence handoffs.
- [ ] Either: (a) GPU plugin works + FPS A/B measured, or (b) GPU stays broken but UI sizing is fixed via E and a follow-up session tackles Mesa.

---

## Explicit Non-Goals

- Re-deriving what we already know. Task 21, Phase 2.1, Phase 2.2 are done; don't re-open their specs.
- Rebuilding LWJGL. That's only a concern if GPU plugin works AND LWJGL-backed code paths get triggered. Not yet.
- Rebuilding RuneLite from source. Out of scope — this is a presentation-pipeline fix.
- Proposing RL-side workarounds (draw distance, plugin toggles) as perf fixes — see memory `feedback_scope_our_pipeline_not_runelite.md`.

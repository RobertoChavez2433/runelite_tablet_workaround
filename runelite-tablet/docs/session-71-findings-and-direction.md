# Session 71 — Findings & Direction

**Date**: 2026-04-16
**Device**: Samsung Tab S10 Ultra (R52X90378YB) — Mali-G720 Immortalis MC12, API 36
**Branch**: `spike/direct-android-surface`
**Status at end of session**: measurement infrastructure extended; direction locked in for session 72 (resume direct-surface spike)

---

## TL;DR

1. **Session 70's "44-51 FPS ceiling" was a measurement artifact.** That number was login-screen compositor damage-FPS (overlay redraws + 2D torch animation), not in-game scene rendering.
2. **Ground truth in-game: ~10 FPS** as reported by RuneLite's own `FpsPlugin` overlay. That's 4-5× worse than we thought and is the actual blocker.
3. **The VirGL server-profile experiments (H4 `--no-loop-or-fork`, H5 `angle-gl`, H6 `angle-vulkan`) are all dead-ends.** H4 is architecturally incompatible with our validation probe. H5 is statistically equivalent to native-gles. H6 regresses badly and introduces a `GL_INVALID_OPERATION` texture-upload bug that renders all scene geometry as white silhouettes.
4. **`gpu.fpsTarget=60` was a previously-unknown internal cap.** Lifting it revealed login-screen FPS can reach 68-77 — confirms VirGL is not a hard 51-FPS ceiling, but the real ceiling for in-game scene rendering is still ~10 FPS due to something else.
5. **The `spike/direct-android-surface` branch is not infeasible.** It was paused at a concrete next step (dladdr symbol-offset resolution for the hot `PutImage`/`Composite` pointers). Direct-SurfaceView probe on this tablet already hit **120 FPS** — hardware is not the limit.
6. **Observability infrastructure was extended this session.** RuneLite's `client.log` now tails into our `DebugLogServer` under tag `RLClient`/source `runelite`. Gives us pipeline-level visibility into GPU plugin events.

---

## Session Timeline

### Phase 1 — Resume from Session 70 and run H4-H6

Session 70's state file listed three next-step options, which I triaged:

- **H4 `--no-loop-or-fork`** (predicted negligible gain, tested it anyway) → **REJECTED (architectural)**. The VirGL server exits after the first client disconnects. Our launch flow runs a glxgears validation probe before RuneLite; `--no-loop-or-fork` kills the server when the probe ends. RuneLite then hits "lost connection to rendering server on 8 read -1 22". Evidence: `VIRGL_WATCHDOG: VirGL server died mid-session (PID=21605 exit=127) — GPU rendering will fail` + `virgl-server.log: client: VTEST_CLIENT_ERROR_INPUT_READ`.
- **H5 `angle-gl`** → **REJECTED (no gain)**. Login-screen post-boot samples `29.6, 43.6, 47.6, 49.6, 44.4, 44.8, 49.2, 64.2, 59.8, 47.0, 45.4, 48.0, 40.4, 51.6, 45.4`. Mean ≈ 47 FPS. Max 64.2 is within native-gles's 59.5 max envelope (session 70 measured).
- **H6 `angle-vulkan`** → **REJECTED + REGRESSION**. User reported ~10 FPS in-game. ANGLE repeatedly errored on `texImage2D` with `GL_INVALID_OPERATION` (0x0502) at `TextureGL.cpp:276`. Scene geometry rendered as white silhouettes because textures never uploaded. Proof-by-screenshot: `docs/logs/s71-color-issue-01.png`.
- **llvmpipe** was on session 70's list; user caught that memory (`.claude/memory/MEMORY.md:94`, `.claude/specs/2026-04-14-120fps-rendering-pipeline-spec.md:25`) already has it ruled out as a dead end (5-15 FPS for GPU plugin, 1.8 FPS measured with inspect). I'd failed to cross-check. Saved feedback memory `feedback_cross_check_notes_against_memory.md`.

### Phase 2 — Infrastructure the sentinel-file override

Added to `launch-runelite.sh` (at line ~191, after `parse_launcher_args`): a sentinel reader that picks up `$HOME/.rlt-virgl-profile-override` and uses it as the VirGL server profile when no CLI flag overrides. Lets you `adb run-as com.termux echo profile > /data/data/com.termux/files/home/.rlt-virgl-profile-override` to flip profiles without rebuilding the APK. Now reverted to empty (native-gles default).

### Phase 3 — Permission regression after reinstall

`adb install -r` revokes `com.termux.permission.RUN_COMMAND`. The setup UI's `EnablePermissions` step only checks `termux.properties allow-external-apps=true`, not the Android permission grant — so it marks "Configure Permissions" Done while the permission is actually missing. Setup silently fails with `java.lang.SecurityException: Not allowed to start service Intent { act=com.termux.RUN_COMMAND ... }`.

**Workaround (applied):** `adb shell pm grant com.runelitetablet com.termux.permission.RUN_COMMAND`

**Fix worth doing (deferred):** `EnablePermissions` setup step should also check `checkSelfPermission(RUN_COMMAND)` before marking Done.

### Phase 4 — Observability extension

Added three pieces to give us pipeline-level visibility into RuneLite's own rendering behavior:

1. **`RL-LOG-TAIL`** (launch-runelite.sh): spawns a background `tail -F /…/client.log` that filters for `GpuPlugin|VAOList|rlawt|FpsPlugin|OpenGL|FrameBuffer|ShaderGen|RuneLite -|ERROR|WARN` and pipes matching lines to `/system/bin/log -t RLClient`. Tail is cleaned up in the EXIT trap.
2. **DebugLogServer extension** (`DebugLogServer.kt`): subscribed to `RLClient:V` logcat tag. Routes any `RL*` tag as `source="runelite"` in the JSON stream. HTML viewer has new `runelite` filter pill with orange left-border (`.src-runelite`).
3. **Opt-in FPS probe** (launch-runelite.sh, gated by `RLT_DEBUG_FPS_PROBE=1`): writes `gpu.fpsTarget=0`, `fpscontrol.maxFps=999`, `fpscontrol.drawFps=true` to every RuneLite profile, backing up the original as `*.rlt-orig`. When the flag is OFF (default), restores from backup. Used this session to ground-truth the 10 FPS number.

### Phase 5 — Ground-truth the FPS number

With probe enabled, logged in at Lumbridge-adjacent location (not the Grand Exchange — GE is worst-case workload). FpsPlugin overlay rendered in upper-right corner showing **10 FPS** in-game steady state.

Compositor damage-FPS in the same window: 40-46 FPS. **The 30+ FPS delta is overlay redraws** — FpsPlugin redraws its own number continuously, producing damage events between real scene frames.

**Implication:** `damage-triggered redraws FPS` is not a reliable proxy for user-visible FPS when any per-second overlay is active. Session 70 conclusions that referenced this metric for in-game performance need re-evaluation. The login screen still registers accurately (no GPU plugin scene, only 2D animation).

---

## Key Findings

### F1 — Session 70 "VirGL ceiling" was login-screen only

All session 70 measurements were taken on the RuneLite login screen. That's a 2D compositor-paced workload (torch flame animation). It's fundamentally different from in-game workload (GPU plugin scene render → FBO → blit → AWT canvas → X11 window). The ~44-51 FPS session 70 measured represents the **login-screen damage event rate**, which is bounded by something close to but not identical to the in-game path.

In-game with GPU plugin actually rendering 3D scenes, RuneLite runs at ~10 FPS. That's the real number.

### F2 — `gpu.fpsTarget=60` was an internal cap masking the ceiling

The RuneLite GPU plugin self-limits to a configurable target FPS. Default from user config: 60. With probe on (`gpu.fpsTarget=0`), login-screen FPS climbed to 68-77 peaks. In-game it did not change — scene rendering hits the real ceiling much earlier. But this does **invalidate** the conclusion "VirGL structurally caps at 51 FPS" since we never actually measured past 60 until this session.

### F3 — H6 ANGLE-Vulkan's `GL_INVALID_OPERATION` on `texImage2D`

While H6 was selected as a profile, all scene textures rendered as solid colors (white player models, flat walls). Cause: ANGLE's GL backend emits `GL_INVALID_OPERATION` repeatedly on texImage2D calls at `renderergl_utils.cpp:3065 TextureGL.cpp:setImageHelper:276`. Textures never upload. 2D UI (minimap, chat, icons) still rendered correctly because UI uses a different path.

**Conclusion:** ANGLE-Vulkan is not viable as a shipping configuration without upstream ANGLE fixes. Keep off.

### F4 — Direct-surface spike was paused, not killed

`docs/hybrid-x11-iteration-log.md` (last updated session 66-ish) ends with a concrete, actionable next step:

> extend `DamageTraceV2` to log `dladdr` base addresses and compute `fn - dli_fbase` offsets for the hot `PutImage` and `Composite` pointers
> map those offsets to the unstripped local `runelite-tablet/app/.cxx/.../arm64-v8a/libXlorie.so`
> instrument or optimize the resolved implementation directly

Prior evidence in the log:

- App-owned direct SurfaceView probe sustains **120 FPS** on this tablet → hardware is not the limit.
- `present after-flips = 0 FPS` → Present extension is completely inactive in our pipeline. Every frame goes through the slower damage-copy path.
- The hot PutImage path is neither `fbPutImage` nor `miPutImage` — it's a GC-installed PutImage op inside the extracted `libXlorie.so` that `dladdr` reports as `sym=unknown`.
- `DamageTraceV2` and `XlorieIdentity` instrumentation is wired and emits live traces.

### F5 — Permissions UI is flaky across reinstalls

See Phase 3 above. Concrete defect in `EnablePermissions` setup step. Low-effort fix, but not this session.

---

## Observability Now Available

| Component | Tag / Source | What it provides |
|-----------|--------------|------------------|
| Kotlin AppLog | `source=kotlin`, tag=varies | All app events (SETUP, LAUNCH, CMD, STATE, SCRIPT, AUTH, SURFACE, BUFFER, GL, JANK, FRAME) |
| Shell scripts | `source=shell`, tag=RLT-SHELL / launcher tags | launch-runelite.sh echo output via AppLog.shell |
| Xlorie native compositor | `source=native`, tags LorieNative/gles-renderer/XlorieCaps/Xlorie | frame timing, buffer lifecycle, GL init |
| **RuneLite client.log** (NEW) | **`source=runelite`, tag `RLClient`** | **GpuPlugin/VAOList/rlawt/FpsPlugin/ShaderGen events** |
| DebugLogServer HTML | http://\<tablet-ip\>:8099/ | WebSocket stream, filterable by source/tag/level/corrId |

---

## Current Device State

- APK: built from this session's changes, installed on R52X90378YB.
- `com.termux.permission.RUN_COMMAND`: granted.
- VirGL sentinel: **empty** (default native-gles).
- FPS probe: **off** (default). Profile restored from `.rlt-orig` on next launch.
- `.rlt-virgl-profile-override`: absent.
- RuneLite session from this session: should have been closed by now; if anything lingers, `aggressiveShutdown` in `RuneLiteSessionService.kt` cleans up.

---

## What NOT to Do Going Forward

Captured as feedback memories in `~/.claude/projects/C--Users-rseba-Projects-Tablite/memory/`:

- **Never propose RuneLite-side workarounds** (draw distance, plugin toggles, etc.) as performance fixes. The user wants the pipeline fixed with default RL settings. (Memory: `feedback_scope_our_pipeline_not_runelite.md`)
- **Always cross-check research docs against memory + specs** before adding options to todo lists. I missed the llvmpipe already-ruled-out note in session 70's doc. (Memory: `feedback_cross_check_notes_against_memory.md`)

---

## Direction for Session 72

**Resume the direct-surface spike.** Next concrete step is in the per-slice plan: `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md`.

Starting slice (S0): extend `DamageTraceV2` to log `dladdr` base + `fn - dli_fbase` offsets for the hot `PutImage` / `Composite` function pointers; build; capture evidence; resolve to symbol names in `libXlorie.so`. From there, decide whether to instrument that implementation or replace it with an AHARDWAREBUFFER-backed fast path.

**Do NOT open session 72 on the stock path.** We've milked that branch of investigation dry for now. The path forward is architectural: move presentation off the serialized X11 `PutImage` pipeline.

---

## Artifacts Produced This Session

- `runelite-tablet/docs/fps-ceiling-research-session71.md` — H4/H5/H6 detailed findings (committed in same session)
- `runelite-tablet/docs/session-71-findings-and-direction.md` — this file
- `runelite-tablet/docs/logs/s71-*.png` — screenshots of each test state, including the angle-vulkan texture breakage and the clean native-gles login screen
- `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md` — per-slice plan (see separate file)
- Launch script gains: sentinel reader (lines ~191-203), observability block (lines ~1104-1160), EXIT trap hook, PID declaration
- DebugLogServer gains: `RLClient:V` subscription, source-routing by tag prefix, `runelite` filter pill in viewer HTML
- Memory: 2 new feedback files

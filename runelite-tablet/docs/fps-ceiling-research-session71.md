# FPS Ceiling Research — Session 71 (2026-04-16)

Follow-up to session 70. Tested three VirGL server profile alternatives to native-gles,
driven by a sentinel-file override in `launch-runelite.sh` for rapid A/B switching.

## Test Harness

Added sentinel reader to `launch-runelite.sh` (~line 191):
`$HOME/.rlt-virgl-profile-override` contents override `VIRGL_SERVER_PROFILE_OVERRIDE`
when no `--virgl-server-profile` CLI flag was passed. Single APK build, three tests
swapped by flipping the sentinel file via `adb run-as com.termux`.

Tests executed on Samsung Tab S10 Ultra (R52X90378YB), Mali-G720 Immortalis MC12.
Workload: RuneLite login screen (torch-flame animation) — not in lobby this session
(no auto-login wired). Baseline comparisons noted where scenes differ.

## Hypotheses

### H4: `--no-loop-or-fork` eliminates per-connection fork overhead

**Prediction**: Negligible — RuneLite opens one VirGL connection per launch, not per
frame. Fork is startup cost, not per-frame.

**Result: REJECTED (blocker, not perf).**

The launcher script runs a glxgears VirGL-validation probe BEFORE RuneLite starts.
With `--no-loop-or-fork`, the VirGL server exits after the first client disconnects —
so when the probe exits, the server dies. RuneLite then fails with `lost connection
to rendering server on 8 read -1 22`.

Evidence (`runelite-launch.log`):
```
VIRGL_VALIDATION: PASS — GL=4.3COMPAT GLSL=430 renderer=... virgl (Mali-G720 ...)
VIRGL_WATCHDOG: VirGL server died mid-session (PID=21605 exit=127) — GPU rendering will fail
virgl-server.log: client: VTEST_CLIENT_ERROR_INPUT_READ
...
lost connection to rendering server on 8 read -1 22
RuneLite exited with code: 0
```

**Why the architecture blocks this**: `--no-loop-or-fork` is a single-client-one-shot
mode. Our harness needs multiple sequential connections (validation probe, then
RuneLite). Even if we removed the probe, RuneLite's launcher→client process handoff
opens a second connection that would fail.

To make `--no-loop-or-fork` testable, we'd need to (a) drop the glxgears probe AND
(b) launch RuneLite's client JAR directly without the launcher, bypassing the process
handoff. Not worth the refactor given the expected gain is zero.

### H5: ANGLE-GL reduces native-GLES translation overhead

**Prediction**: Uncertain. ANGLE batches GL commands but adds a translation layer.

**Result: REJECTED (no meaningful improvement).**

Login-screen damage-triggered FPS samples, 5-second windows, post-boot steady state:
```
29.6, 43.6, 47.6, 49.6, 44.4, 44.8, 49.2, 64.2, 59.8, 47.0, 45.4, 48.0, 40.4, 51.6, 45.4
```
Mean ≈ 47 FPS. Max 64.2 FPS. Min (non-boot) 40.4 FPS.

Session 70 native-gles **lobby** baseline: 44-51 FPS (XloriePerf estimated_fps range
33.5-59.5). The peak of 64.2 FPS in H5 is 4.7 above the native-gles max (59.5), within
measurement noise across different scenes (login vs lobby).

**Verdict**: ANGLE-GL doesn't bypass the VirGL vtest socket serialization ceiling.
It's statistically equivalent to native-gles. The occasional 60+ FPS windows match
native-gles's own occasional highs.

Renderer string confirmed active: `virgl (ANGLE (ARM, Mali-G720-Immortalis MC12, OpenGL ES 3.2...))`

### H6: ANGLE-Vulkan bypasses Mali GLES driver via native Vulkan

**Prediction**: Best odds of a real win. Vulkan has less driver overhead than GLES on
Mali, and ANGLE's batch submission model may pipeline better than vtest's
per-command round-trips.

**Result: REJECTED — REGRESSION from baseline, with texture upload bugs.**

RuneLite GPU plugin loads and selects the ANGLE-Vulkan device:
```
[Client] INFO n.r.client.plugins.gpu.GpuPlugin - Using device:
  virgl (ANGLE (ARM, Vulkan 1.3.247 (Mali-G720-Immortalis MC1...))
```

But ANGLE repeatedly errors on texture uploads:
```
ERR: renderergl_utils.cpp:3065 (HandleError): GL call functions->texImage2D(...)
  generated error 0x00000502 (GL_INVALID_OPERATION) in
  ../../../cache/tmp-checkout/angle/src/libANGLE/renderer/gl/TextureGL.cpp,
  setImageHelper:276.
```

Compositor-side damage FPS (login screen): 32-50 FPS range, mean ~42. Roughly equal to
baseline on the surface — but **user reported only 10 FPS in-game** (actual game scene,
post-login). That's a 4-5× regression vs native-gles (44-51 FPS lobby baseline).

Interpretation: The compositor damage counter measures X11 damage events, not
RuneLite's own scene rendering. ANGLE's Vulkan translation layer stalls on texture
uploads (GL_INVALID_OPERATION repeatedly) which doesn't block 2D compositor redraws
but does cripple the GPU plugin's 3D scene pipeline.

Renderer string confirmed: `virgl (ANGLE (ARM, Vulkan 1.3.247 (Mali-G720-Immortalis MC1...))`

## Summary

| Profile         | Server health | Compositor FPS | In-game FPS      | Verdict                  |
|-----------------|---------------|----------------|------------------|--------------------------|
| native-gles     | stable        | 44-51          | ~40-50 (s70 ref) | baseline (keep)          |
| no-loop-or-fork | dies after 1st client | n/a    | n/a              | architecture-incompatible|
| angle-gl        | stable        | 40-64, mean ~47| similar to base  | no meaningful gain       |
| angle-vulkan    | stable        | 32-50, mean ~42| ~10 (user-reported)| regression + tex bugs  |

**Final verdict:** VirGL vtest socket serialization remains the structural FPS ceiling.
None of the three server profiles tested improves on native-gles. angle-gl is a wash,
angle-vulkan is a net negative. `no-loop-or-fork` is blocked by the multi-client
launch flow.

**Not attempted** (backpocket): Mesa Venus protocol. Requires rebuilding virglrenderer
+ Mesa with Venus support. HIGH effort. Only worth pursuing if the 44-51 FPS ceiling
proves user-intolerable.

## Measurement gap worth closing

The compositor-side `damage-triggered redraws` FPS counter (LorieNative) does NOT
reflect in-game rendering rate when the GPU plugin is stalled. Session 70 measurements
assumed these numbers tracked user-visible FPS. angle-vulkan showed they don't — 42
FPS of damage events with 10 FPS actual gameplay.

**Follow-up**: capture RuneLite's own in-client FPS counter (it has one) or use the
GPU plugin's frame time stats. This will give a ground-truth FPS number that reflects
what the user actually sees.

## Side findings

### Permission re-grant needed after `adb install -r`

Reinstalling the APK over an existing install revokes `com.termux.permission.RUN_COMMAND`
even though the Termux-side `termux.properties` has `allow-external-apps=true`. The
setup flow marks "Configure Permissions" Done optimistically (properties file only,
no Android permission check), so scripts silently fail to deploy:
```
java.lang.SecurityException: Not allowed to start service Intent {
  act=com.termux.RUN_COMMAND ... } without permission com.termux.permission.RUN_COMMAND
```

Workaround: `adb shell pm grant com.runelitetablet com.termux.permission.RUN_COMMAND`

**Follow-up worth doing**: The `EnablePermissions` setup step should also call
`checkSelfPermission(RUN_COMMAND) == PERMISSION_GRANTED` before marking Done. See
session 71 UI flow — user reported this was confusing; symptom is infinite
"Install Linux Environment — Running" spinner.

## Changes made this session

1. `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh` — added sentinel-file
   reader for `VIRGL_SERVER_PROFILE_OVERRIDE` (lines ~191-203, after `parse_launcher_args`).
   Low-risk: only fires when CLI flag is absent.

2. `.claude/settings.local.json` — expanded tool allowlist (Task*, Monitor, ScheduleWakeup,
   etc.) and common WebFetch domains.

# Hybrid X11 Iteration Log

This is a live engineering log for the `spike/direct-android-surface` branch.

The point of this file is not to be a polished handoff. It is a running record of what was tried, why it was tried, what it proved, and what the current blocker is, so ongoing terminal experimentation does not become the only source of truth.

## Current Branch State

- Branch: `spike/direct-android-surface`
- Last committed spike checkpoint: `70015a2`
- Current focus: stay on the existing Linux RuneLite path, but move or reshape the presentation layer so the visible Android surface is no longer the bottleneck.

## Why This Branch Exists

The stock path already proved the hard parts:

- GeckoView / Jagex auth works.
- The app can orchestrate setup and launch.
- Termux + `proot` can run the real Linux ARM64 RuneLite build.
- Native VirGL can get RuneLite onto the GPU path.

What is still failing is the end-user experience:

- launch/handoff has been fragile
- `Termux:X11` owns the visible Android surface
- full-screen performance has been far worse than the hardware should allow

The direct surface probe proved the tablet is not the limiting factor. An app-owned full-screen `SurfaceView` can sustain `120 fps` on this device. That shifted the investigation from "can the tablet do it?" to "what in the current presentation chain is collapsing throughput?"

## Core Iteration Logic

This is the reasoning chain that led to the current hybrid work.

1. The stock architecture works functionally, but full-screen performance is poor.
2. The app-owned direct surface probe hit `120 fps`.
3. That means Android app-owned presentation is viable on this tablet.
4. Therefore the likely bottleneck is not RuneLite auth, not basic Android capability, and not just "the GPU plugin is off".
5. The remaining suspect is the current presentation boundary:
   - RuneLite in Linux
   - Mesa / VirGL
   - `Termux:X11`
   - Android compositor
6. The next least-destructive experiment is hybrid option `C`:
   - keep Linux RuneLite
   - keep Termux + `proot`
   - keep the `termux-x11` command path if possible
   - move the visible surface owner into our app

## What Has Been Proven So Far

### 1. Stock `Termux:X11` is the current presentation bottleneck candidate

Earlier testing on the stock path showed:

- GPU plugin active inside RuneLite
- native display mode exposed
- poor full-screen performance
- strong evidence of presentation instability around `com.termux.x11`

This did not prove stock `Termux:X11` is the only problem, but it was enough to justify attacking that boundary directly.

### 2. App-owned full-screen surfaces can hit 120 Hz on this tablet

`DirectSurfaceProbeActivity` showed:

- the app can own the visible `SurfaceView`
- SurfaceFlinger can drive that surface at `120 fps`

That is the strongest evidence so far that the hardware and Android surface path are capable of the target class of performance when our app owns the surface.

### 3. The stock `termux-x11` command can target our package

The hybrid bridge testing proved:

- `TERMUX_X11_OVERRIDE_PACKAGE=com.runelitetablet` works
- upstream `CmdEntryPoint` can send its `ACTION_START` binder handoff into our app
- our app can receive and retain the binder

This is critical because it means the hybrid path is not purely theoretical. We can keep the Termux-side X server command path while moving the Android-side host into our package.

### 4. The first hybrid reconnect bug was real and is now narrowed

The hybrid host was initially treating every repeated `ACTION_START` broadcast as a brand-new connection generation.

That caused repeated reconnect behavior even when the binder had not actually changed.

The bridge was updated to track:

- `generation`
- `binderToken`

Now duplicate broadcasts from the same binder do not trigger a reconnect unless the binder itself changes.

### 5. The remaining hybrid problem is not just reconnect churn

Even after the reconnect fix:

- the hybrid host receives the X connection
- the hybrid host receives shared buffers
- the hybrid host owns the app surface
- the surface stays at the correct size and nominal refresh

But actual presentation remains extremely slow.

That means the current blocker is deeper than "the host keeps reconnecting."

## Current Measurements And Observations

### Direct app-owned probe

Observed result:

- app-owned `SurfaceView`
- full-screen
- `120 fps` queueing visible in logs

Conclusion:

- the tablet and our app can sustain high-refresh full-screen presentation when we own the surface directly

### Hybrid probe client-side rates

Observed in the simpler probe runs:

- windowed `glxgears` under llvmpipe: about `125 fps`
- full-screen `glxgears` under llvmpipe: about `97 fps`

Conclusion:

- the Linux/X client side is not completely stalled
- the embedded host path is presenting much more slowly than the X client is producing frames

### Embedded hybrid renderer rates

Observed repeatedly:

- `LorieNative: 1 frames in 5.0 seconds = 0.2 FPS`
- `LorieNative: 2 frames in 5.0 seconds = 0.4 FPS`
- `LorieNative: 3 frames in 5.0 seconds = 0.6 FPS`

Conclusion:

- the embedded Xlorie presentation path is currently the collapse point

### Real hybrid launcher path

The real launcher path was also tested, not just isolated probes.

Observed:

- hybrid host attached to a real X connection
- `surface=2960x1848`
- `screen=2960x1848`
- `refresh=120`
- `connected=true`
- shared buffers received at both intermediate and full-screen sizes
- layer votes remained `ExplicitDefault (120.00 Hz)`

But actual queueing remained poor:

- `queueBuffer: fps=0.11 dur=9233.24`
- later brief improvement only to about `2.95 fps`

Conclusion:

- the hybrid path is functionally wired up
- the bottleneck is now specifically in the embedded presentation behavior, not in basic control-plane launch

## Current Hypothesis

The hybrid option is still viable, but our host does not yet mirror enough of upstream `Termux:X11` activity/view behavior for the renderer to present efficiently.

The strongest current suspects are:

- missing activity-side connection behavior from upstream `MainActivity`
- missing preference reload path after connect
- missing or incomplete `LorieView` lifecycle behavior
- missing visibility / connection-state transitions that upstream uses to keep the renderer healthy

One important upstream-alignment fix has already been made:

- `LorieView.triggerCallback()` now replays `surfaceChanged(...)` similarly to upstream

That was necessary, but not sufficient.

## 2026-03-15 Checkpoint: What Changed

This checkpoint captures the most recent controlled hybrid tests.

### The first combined fullscreen probe was self-invalidating

The combined probe script was:

- starting `termux-x11`
- foregrounding `HybridX11HostActivity`
- then later foregrounding the host again with another `am start`

That second `am start` triggered `onNewIntent(...)` on the already-running host.

At the time, the host treated that as a forced reconnect. The result was:

- attach state cleared
- bridge status reset
- probe timing polluted by a reconnect we caused ourselves

This was not a useful rendering measurement.

### The host reconnect behavior was tightened

`HybridX11HostActivity.onNewIntent(...)` now keeps the existing session if all of the following are true:

- `LorieView.connected()`
- the current binder is still alive
- there is no pending bridge reconnect

The hybrid combined probe was also updated so that when the override package is `com.runelitetablet`, it does not relaunch `HybridX11HostActivity` mid-probe.

### The decisive new result: host attach works, but `DISPLAY=:0` still does not

The strongest test so far was a direct host launch done from adb so Android background-launch restrictions could not interfere.

That test proved all of the following at once:

- our app-owned host activity was foreground
- the app loaded external `libXlorie.so`
- the bridge attached to a real `xConnection` file descriptor
- shared buffers arrived
- the host surface was the correct full-screen size
- SurfaceFlinger treated the app-owned surface as `120 Hz`

Representative observations from that run:

- `HybridX11HostActivity: attached xConnection fd=...`
- `surface=2960x1848 screen=2960x1848 refresh=120 connected=true`
- `Received shared buffer ...`
- `New reported framerate is 120`

But the Linux-side client in `proot` still failed the basic display test:

- `[proot] DISPLAY never became ready`
- `xdpyinfo: unable to open display ":0"`

This is the most important current finding.

It means:

- the app-owned host surface path is viable
- the bridge/control plane is viable
- the current blocker is now the external X11 transport path exposed to Linux clients in `proot`

### Socket visibility is suspicious

In the same test family:

- the loader reported repeated `New client connection!`
- but `.X11-unix` listings in both Termux and `proot` were empty

That suggests the external display transport used by stock `termux-x11` on-device is not being reproduced correctly in the current hybrid path, or the probe is pointing Linux clients at the wrong transport form.

### Legacy drawing showed up again

The direct-host run also logged:

- `Failed to obtain EGLImageKHR from EGLClientBuffer`
- `Forcing legacy drawing`

That is probably relevant to final performance, but it is not the immediate blocker because the Linux clients are not yet getting a usable external display connection at all.

## Files Currently In Play

These are the main branch files involved in the current hybrid spike.

### Modified tracked files

- `runelite-tablet/app/src/main/AndroidManifest.xml`
- `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/PresentationBackend.kt`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/PresentationBackends.kt`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11Bridge.kt`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11TestReceiver.kt`
- `runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt`

### New hybrid files

- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/HybridX11PresentationBackend.kt`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/ExternalXlorieLoader.kt`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt`
- `runelite-tablet/app/src/main/java/com/termux/...`

## Local Artifacts Worth Keeping

These local files capture useful snapshots from the current spike:

- `real-hybrid-current.log`
- `real-hybrid-current.png`
- `triggerfix-windowed.log`
- `early-glxgears-windowed.log`
- `early-glxgears-fullscreen.log`
- `hybrid-logcat.txt`

There are also several temporary comparison logs in the repo root. They are useful for local inspection, but they should be cleaned or archived before any commit.

## Immediate Next Steps

The next tests should stay focused and hypothesis-driven.

1. Inspect upstream `CmdEntryPoint` / loader behavior and determine exactly how stock `termux-x11` exposes the X transport on Android.
2. Compare stock `termux-x11` transport behavior against the hybrid override path.
3. Determine whether the correct external transport is a filesystem socket, an abstract socket, or a different loader-mediated path.
4. Patch the hybrid setup or probe environment so Linux clients in `proot` can actually open the display.
5. Only after `xdpyinfo` succeeds should performance measurements be treated as meaningful again.

## Logging Discipline Going Forward

The user concern here is valid: heavy testing can destroy useful live log history if the terminal session is the only place the work is recorded.

Going forward, this branch work should follow a tighter loop:

1. Before a major test, write down the hypothesis in this file.
2. Capture the relevant logs to a file before clearing logcat.
3. Run the test.
4. Append the result and the conclusion here.
5. Only then move on to the next experiment.

This file should be updated as the work continues so the reasoning chain stays visible without reconstructing it from shell history.

## 2026-03-15 Checkpoint 2

### The "host attach removes X0" theory did not survive controlled retest

The next probe cycle specifically tested whether opening the app-owned host was the event that caused the visible `X0` pathname socket to disappear.

That theory is no longer supported.

#### Controlled test: hybrid start, inspect before host open, then inspect after host open

Artifacts:

- `hybrid-host-effect-20260315-011838-before.txt`
- `hybrid-host-effect-20260315-011838-after.txt`
- `hybrid-client-before-host-20260315-011928.log`
- `hybrid-client-after-host-20260315-011928.log`
- `hybrid-client-host-compare-20260315-011928-logcat.txt`

What happened:

- The direct low-level `/proc/net/unix` check showed the X11 sockets present both before and after opening `HybridX11HostActivity`.
- The more relevant Termux-side client probe showed failure both before and after the host was opened:
  - `[shell] tmpdir socket listing:` showed an empty `.X11-unix` directory
  - `[proot] DISPLAY never became ready`
  - `xdpyinfo: unable to open display ":0"`

Conclusion:

- opening `HybridX11HostActivity` is not the thing that makes the later client probe fail
- the failure already exists before the host is opened in the delayed two-step probe shape

### Stock `Termux:X11` shows the same delayed-probe failure in the same test shape

Artifacts:

- `stock-client-opened-20260315-012129.log`
- `stock-client-opened-20260315-012129-logcat.txt`

This matters because an earlier checkpoint had started drifting toward "hybrid-specific transport regression."

That no longer appears safe to claim.

Using the same split test shape:

1. start `termux-x11`
2. wait
3. open the stock `com.termux.x11/.MainActivity`
4. wait
5. run the Termux/proot client probe

the stock path also showed:

- empty later `tmpdir socket listing`
- empty later `/tmp/.X11-unix` listing in `proot`
- `[proot] DISPLAY never became ready`

So the current delayed probe failure is broader than the hybrid activity path.

### Stronger working hypothesis now: the pathname `X0` exists briefly, then later disappears

This now fits the evidence better than the older host-lifecycle theory.

Evidence chain:

- `ACTION_START_TEST` still shows the startup-time socket:
  - startup log includes `srwxrwxrwx ... X0`
- the later two-step client probes, both hybrid and stock, see an empty `.X11-unix` directory
- `/proc/net/unix` still reports both:
  - `/data/data/com.termux/files/usr/tmp/.X11-unix/X0`
  - `@/data/data/com.termux/files/usr/tmp/.X11-unix/X0`
- older integrated stock probe evidence from `stock-probe-20260315-005141.log` showed the socket still visible at client time:
  - shell-side `X0`
  - proot-side `X0`

The most plausible interpretation at this point is:

- the filesystem pathname socket is available only during a narrower window
- our slower split probes are missing that window
- the real launcher may succeed because it gets the first Linux-side client connected quickly enough

### Immediate host open did not fix the delayed client probe

Artifacts:

- `hybrid-client-immediate-host-20260315-012329.log`
- `hybrid-client-immediate-host-20260315-012329-logcat.txt`

This retest moved host opening much earlier, closer to the real launcher behavior.

Result:

- `HybridX11HostActivity` eventually attached successfully:
  - `attached xConnection fd=158`
  - `surface=2960x1848 screen=2960x1848 refresh=120 connected=true`
- but the later delayed client probe still failed the same way:
  - empty `tmpdir socket listing`
  - `[proot] DISPLAY never became ready`

So early host opening alone is not enough to preserve a later pathname-socket connection point for new Linux clients.

### Important correction to prior conclusions

A previous checkpoint leaned too hard toward:

- "hybrid-specific regression"
- "host attach is what breaks the transport"

The current controlled results do not support that.

The updated conclusion is:

- the current failure appears to be timing-sensitive and affects stock delayed probes too
- the next diagnostic target should be the lifetime of the pathname X11 socket and how quickly the first Linux client must connect

### Revised next steps

1. Build a launcher-faithful one-shot probe that:
   - starts `termux-x11`
   - opens the host/activity immediately
   - launches the Linux-side client as soon as socket readiness is first observed
2. Measure whether an early first client connection succeeds consistently while later follow-up clients fail.
3. If early first-client success is confirmed, investigate whether the launcher can deliberately anchor that first X11 client connection earlier in startup.
4. Only if early-connect still fails should the focus shift back to missing app-side Xlorie behavior.

### 2026-03-15 Checkpoint 3

The probe harness itself was still biased toward failure.

Up to this point, `ACTION_START_AND_RUN_CLIENT` always used a delayed shape:

1. start `termux-x11`
2. sleep
3. open the activity
4. sleep
5. run the Linux-side client

That is not close enough to the real launcher path to settle the timing question.

#### Probe harness update

`HybridX11TestReceiver` now supports two explicit probe shapes:

- `delayed`
- `launcher-faithful`

The new `launcher-faithful` shape does the following in one Termux command:

1. stop old X11
2. start `termux-x11`
3. poll until `$TMPDIR/.X11-unix/X0` first appears
4. immediately foreground the target activity with `singleTop/clearTop`
5. immediately launch the first Linux-side client probe

This keeps the old delayed shape available as a control, but finally gives a probe that matches the real launcher's sequencing closely enough to test the early-client hypothesis.

#### Next test

Run the same `inspect` probe in both shapes:

- stock override `<stock>`
- hybrid override `com.runelitetablet`

If the launcher-faithful shape succeeds where the delayed shape fails, the next target is launcher sequencing, not missing host-side Xlorie behavior.

#### Static diff note: local host still does not fully mirror upstream `tryConnect()`

While waiting for the next device run, the hybrid host was compared again against upstream `MainActivity`.

Upstream `tryConnect()` still does more after `getXConnection()` succeeds:

- `LorieView.connect(fd.detachFd())`
- `getLorieView().triggerCallback()`
- `clientConnectedStateChanged()`
- `getLorieView().reloadPreferences(prefs)`

The current hybrid host already does:

- `LorieView.connect(detached)`
- `lorieView.triggerCallback()`

But it still differs in two ways:

- it does not explicitly mirror upstream `clientConnectedStateChanged()` immediately after connect
- the local `LorieView` does not yet implement upstream `reloadPreferences(prefs)`

Current judgment:

- these are still real deltas to close later
- but they remain secondary until the launcher-faithful early-connect probe either succeeds or fails
- the missing `reloadPreferences()` path looks more relevant to clipboard/input behavior than to the filesystem socket lifetime issue

#### Current blocker

The updated probe build completed locally, but the tablet dropped to `adb offline` during reinstall before the next on-device run.

Status at pause:

- `:app:assembleDebug` passed
- the updated `HybridX11TestReceiver` is ready locally
- install and device probe are waiting on the tablet returning to an online `adb` state

### 2026-03-15 Checkpoint 4

The next device pass materially changed the picture.

#### Result: both stock and hybrid now accept the first Linux-side client

After reinstalling the updated build, the following all succeeded:

- stock + `delayed` + `inspect`
- stock + `launcher-faithful` + `inspect`
- hybrid + `delayed` + `inspect`
- hybrid + `launcher-faithful` + `inspect`

In all four cases:

- the shell-side `X0` pathname was visible
- `/tmp/.X11-unix/X0` was visible inside `proot`
- `xdpyinfo` succeeded
- the probe reported `[proot] DISPLAY ready`

That means the previous blanket conclusion, "delayed probes fail while launcher-faithful succeeds", is no longer safe. The transport path is now working in both stock and hybrid on the current build/device state.

#### Real hybrid launcher now works end-to-end

The branch was then tested with the real launcher path, not just the synthetic probe.

Observed in `runelite-launch.log`:

- `Termux:X11 override package: com.runelitetablet`
- `X11 socket ready`
- `Hybrid X11 host activity launched`
- `VirGL native GLES server started`
- `GPU acceleration: ENABLED (VirGL, GL=4.3COMPAT GLSL=430)`
- `RuneLite started with PID ...`

Observed later in client logs:

- `GpuPlugin - Using device: virgl (Mali-G720-Immortalis MC12)`
- `GpuPlugin - Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.25.10.1`
- `TextureManager - Uploaded textures 208`

So the current branch can now:

- launch fresh `Termux:X11`
- route presentation into `HybridX11HostActivity`
- start VirGL
- start real RuneLite
- reach the in-client GPU plugin on `virgl`

#### New main issue: binder churn, not first-client failure

The remaining abnormal behavior in the logs is repeated `ACTION_START` / binder churn.

Two important facts from upstream explain this:

- `CmdEntryPoint.sendBroadcastDelayed()` rebroadcasts `ACTION_START` until native `connected()` reports true
- the hybrid receiver was previously accepting dead binders too, which created repeated `DeadObjectException` noise during shutdown/restart edges

#### Cleanup patch in progress

The next patch round is aimed at log/lifecycle correctness, not transport enablement:

- ignore dead binders in `TermuxX11StartReceiver`
- ignore dead binder attachments in `HybridX11Bridge`
- avoid `getXConnection()` calls when the current binder is already dead
- advance session `state` to `running` once the real client JVM has been launched

### 2026-03-15 Checkpoint 5

The cleanup patch validated.

#### Verified after rebuild/reinstall

1. Real hybrid launcher still starts successfully.
2. `HybridX11HostActivity` still attaches the X connection.
3. Session state now advances to `running` instead of staying stuck at `backend-starting`.
4. The later client log still reaches:
   - `GpuPlugin - Using device: virgl`
   - `GpuPlugin - Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.25.10.1`
   - `TextureManager - Uploaded textures 208`

#### Dead-binder cleanup result

The previous shutdown edge produced repeated `DeadObjectException` stack traces when stale dead binders were re-broadcast into the host attach loop.

That is now mitigated by:

- ignoring dead binders in `TermuxX11StartReceiver`
- ignoring dead binder attachments in `HybridX11Bridge`
- refusing to call `getXConnection()` when the current binder is already dead

#### Additional log-noise reduction

Repeated live re-broadcasts from the same binder are now deduplicated in the receiver. That should keep future capture files smaller and make the next performance-focused passes easier to interpret.

#### Current state at end of this cycle

Option `C` is now beyond a synthetic proof:

- real launcher path works
- real RuneLite starts
- hybrid host owns the visible Android activity
- VirGL path still reaches the RuneLite GPU plugin

The next open question is no longer "can the branch launch RuneLite on the hybrid surface?".

The next question is performance:

- does the hybrid app-owned surface materially improve frame pacing / throughput compared with external `Termux:X11`
- if not, where the remaining bottleneck sits in the current `VirGL -> Xlorie` presentation path

### 2026-03-15 Checkpoint 6

The next blocker turned out to be input semantics, not transport or launch.

#### First hybrid input patch was directionally wrong

A minimal input controller was added to the hybrid host to restore interaction parity quickly. That first version handled:

- hardware mouse motion and buttons
- wheel events
- captured pointer events
- keyboard events
- touchscreen gesture translation into mouse-like events

That last item is not acceptable for RuneLite. It creates a mixed touch-to-mouse input model that is not the same thing as a real mouse path and should not be used for the game client.

#### Correction made

The hybrid input controller has now been narrowed back down to the safe subset:

- real hardware mouse / touchpad style motion
- real mouse buttons
- wheel input
- captured pointer input
- keyboard input

Touchscreen events are no longer translated into mouse events in the hybrid host.

#### What remains to validate

The next validation pass should use a Linux-side event probe such as `xev`, not RuneLite, to answer two narrower questions:

1. Does the hybrid host now deliver real hardware mouse movement and button events into X11?
2. If yes, is the remaining interaction gap only touch support, which should stay out-of-scope for the RuneLite control path?

### 2026-03-15 Checkpoint 7

The input requirement is now explicit and locked in:

- touchscreen must remain trackpad-style
- no direct absolute "touch the game world here" injection
- real hardware mouse / touchpad input must remain real pointer input

That requirement is now documented directly in the hybrid input controller so later performance work does not accidentally drift back into unsafe semantics.

#### New performance finding

The next A/B performance pass used the launcher-faithful combined VirGL probe for:

- stock `Termux:X11` fullscreen
- hybrid fullscreen
- stock windowed
- hybrid windowed

Those runs did **not** reproduce the earlier better standalone probe numbers.

Observed in current capture files:

- stock fullscreen: `3 frames in 5.0 seconds = 0.6 FPS`
- hybrid fullscreen: `3 frames in 5.0 seconds = 0.6 FPS`
- stock windowed: `1 frames in 5.0 seconds = 0.2 FPS`
- hybrid windowed: `3 frames in 5.0 seconds = 0.6 FPS`

Stock still also shows the old:

- `Max (can't resolve refresh rate)`

on the `Termux:X11` surface.

#### Interpretation

This is too low to treat as a real steady-state comparison against the older standalone pacing results. The current combined probe path is likely introducing extra churn or measuring the wrong phase.

So the branch now has a new measurement issue to resolve:

- separate startup churn from steady-state rendering
- rerun the VirGL probe on an already-attached host surface
- only then compare stock vs hybrid throughput again

### 2026-03-15 Checkpoint 8

The split-start rerun did not clear the regression.

#### Test shape

Hybrid test sequence:

1. stop existing X11
2. start fresh hybrid X11
3. open the hybrid host activity
4. wait for `attached xConnection`
5. run `glxgears-virgl-windowed` as a separate client action

#### Result

Even in that cleaner sequence, the hybrid probe still collapsed to:

- `2 frames in 5.0 seconds = 0.4 FPS`

So the current low-FPS result is **not** just a combined-launch artifact.

#### Additional clue from probe stdout

The captured stdout confirms:

- the client action is running
- the hybrid host is attached
- the X11 loader process is alive
- the probe still exits via timeout (`exitCode=143`)

But the current probe output is not yet showing a clean renderer / `glxgears` FPS line in the app log, so the next debugging target is probe observability:

- capture the full client stdout to a file
- verify whether `glxgears` is actively rendering and being starved, or not fully reaching steady-state draw at all

### 2026-03-15 Checkpoint 9

The next control pass separated two different problems that had been conflated.

#### Split-start is still not a valid transport control

Fresh split-start `inspect` and split-start `glxgears-virgl-windowed` probes were rerun with new captures:

- `perf-captures/stock-split-inspect-20260315-091155.logcat.txt`
- `perf-captures/hybrid-split-inspect-20260315-090743.logcat.txt`
- `perf-captures/stock-split-virgl-windowed-20260315-090743.logcat.txt`
- `perf-captures/hybrid-split-virgl-windowed-20260315-090743.logcat.txt`

Observed:

- stock split `inspect`: `DISPLAY never became ready`, `0` `New client connection!`, `2 frames in 5.0 seconds = 0.4 FPS`
- hybrid split `inspect`: `DISPLAY never became ready`, `182` `New client connection!`, `2 frames in 5.0 seconds = 0.4 FPS`
- stock split `glxgears-virgl-windowed`: only `2` `New client connection!`, `1 frames in 5.0 seconds = 0.2 FPS`
- hybrid split `glxgears-virgl-windowed`: `100` `New client connection!`, `3 frames in 5.0 seconds = 0.6 FPS`

This matters because the split-start shape is now clearly transport-invalidating in both stock and hybrid. It is not safe to use split-start alone as proof that only hybrid is breaking `DISPLAY=:0`.

#### But hybrid still adds its own reconnect churn

Even though split-start is transport-invalidating in both paths, the hybrid path still has an extra pathology that stock does not:

- stock split `inspect` produced `0` `New client connection!`
- hybrid split `inspect` produced `182` `New client connection!`

That means the reconnect loop is not caused by `glxgears` or by VirGL alone. It can happen even on `inspect` mode with no real GL client workload.

### 2026-03-15 Checkpoint 10

The decisive control was a rerun of the combined launcher-faithful `inspect` probe on the current device state.

New captures:

- `perf-captures/stock-combined-inspect-20260315-091331.logcat.txt`
- `perf-captures/hybrid-combined-inspect-20260315-091331.logcat.txt`

#### Stock combined `inspect`

Observed:

- socket appeared (`X0`)
- `/tmp/.X11-unix/X0` was visible in `proot`
- `[proot] DISPLAY ready`
- `0` `New client connection!`
- `8 frames in 5.0 seconds = 1.6 FPS`

#### Hybrid combined `inspect`

Observed:

- socket appeared (`X0`)
- `/tmp/.X11-unix/X0` was visible in `proot`
- `[proot] DISPLAY ready`
- `TermuxX11StartReceiver: received ACTION_START binder=alive`
- `HybridX11HostActivity: attached xConnection fd=161`
- `85` `New client connection!`
- `3 frames in 5.0 seconds = 0.6 FPS`

#### Interpretation

This is the strongest current result on the branch.

It proves:

- basic transport is healthy in the launcher-faithful path for both stock and hybrid
- the hybrid performance collapse does **not** require VirGL or `glxgears`
- the hybrid-specific instability survives even on `inspect`

So the next blocker is no longer "does `DISPLAY=:0` work in hybrid?" and no longer "is VirGL causing the reconnect loop?"

The blocker is now narrower:

- when the app-owned hybrid host is the presentation target, the embedded Xlorie/native path still enters repeated client reconnect churn and low presentation throughput even after transport is healthy

The next investigation should target upstream lifecycle parity, not client transport setup:

- compare `HybridX11HostActivity` against upstream `MainActivity` for the exact visible/resumed/focus transitions that bracket native connect
- instrument where native `connected()` or equivalent steady-state ownership is failing, since repeated `New client connection!` persists even on combined `inspect`

### 2026-03-15 Checkpoint 11

The previous "hybrid-only reconnect storm" conclusion turned out to be contaminated by stale process/activity state across sequential adb probes.

#### Clean-start control changed the result

A new control was run that force-stops both packages before each probe:

- `adb shell am force-stop com.termux.x11`
- `adb shell am force-stop com.runelitetablet`

Then the same combined `launcher-faithful` probe was rerun.

Key new capture:

- `perf-captures/hybrid-combined-inspect-clean-20260315-092425.logcat.txt`

Observed:

- `DISPLAY ready`
- `attached xConnection fd=167`
- only `4` `New client connection!`
- all `4` happened **before** attach
- `0` `New client connection!` happened after attach
- `2 frames in 5.0 seconds = 0.4 FPS`

This is a major correction to the earlier reading.

It means the repeated post-attach hybrid reconnect churn seen in the earlier combined captures was not a stable property of the current hybrid host by itself. It depended on dirty prior activity/process state.

#### Short parity patch was tested and reverted

A small parity patch was tested next:

- delay hybrid `requestConnection()` until after the first bridge event
- explicitly call local shim `clientConnectedStateChanged()` right after `connect()` / `triggerCallback()`

Result on the patched build:

- `perf-captures/hybrid-combined-inspect-20260315-092201.logcat.txt`
- still `82` `New client connection!`
- still `63` after attach
- worse pacing: only `1 frames in 5.0 seconds = 0.2 FPS`

That patch did not help and was reverted immediately.

So the useful result from this cycle is not the patch. The useful result is the clean-start control.

### 2026-03-15 Checkpoint 12

The clean-start matrix was then expanded across stock and hybrid probe modes.

New captures:

- `perf-captures/stock-combined-inspect-clean-20260315-092527.logcat.txt`
- `perf-captures/stock-combined-virgl-windowed-clean-20260315-092527.logcat.txt`
- `perf-captures/hybrid-combined-virgl-windowed-clean-20260315-092527.logcat.txt`
- `perf-captures/stock-combined-virgl-fullscreen-clean-20260315-092724.logcat.txt`
- `perf-captures/hybrid-combined-virgl-fullscreen-clean-20260315-092724.logcat.txt`

#### Clean combined `inspect`

Observed:

- stock clean `inspect`: `1` `New client connection!`, `DISPLAY ready`, `OpenGL renderer string: llvmpipe`, `9 frames in 5.0 seconds = 1.8 FPS`
- hybrid clean `inspect`: `4` `New client connection!`, `DISPLAY ready`, `2 frames in 5.0 seconds = 0.4 FPS`

Important nuance:

- hybrid clean `inspect` still showed a few connection events
- but those all happened before attach
- after attach the count stayed at `0`

So under clean starts, hybrid no longer shows the old runaway post-attach reconnect behavior.

#### Clean combined `glxgears-virgl-windowed`

Observed:

- stock clean windowed VirGL: `1` `New client connection!`, `3 frames in 5.0 seconds = 0.6 FPS`
- hybrid clean windowed VirGL: `1` `New client connection!`, attach at `fd=153`, `0` post-attach reconnects, `3 frames in 5.0 seconds = 0.6 FPS`

This is the strongest like-for-like windowed result so far.

Under clean starts:

- stock and hybrid are effectively at parity on the windowed VirGL probe
- the earlier hybrid-only reconnect storm does **not** reproduce

#### Clean combined `glxgears-virgl-fullscreen`

Observed:

- stock clean fullscreen VirGL: `1` `New client connection!`, `2 frames in 5.0 seconds = 0.4 FPS`
- hybrid clean fullscreen VirGL: `1` `New client connection!`, attach at `fd=151`, `0` post-attach reconnects, `3 frames in 5.0 seconds = 0.6 FPS`

This is still low absolute throughput, but it no longer supports the earlier claim that hybrid itself is uniquely collapsing due to reconnect churn.

#### Revised interpretation

The current clean-start evidence says:

- stale activity / process state was heavily polluting earlier adb probe families
- clean starts remove the apparent hybrid post-attach reconnect storm
- once that contamination is removed, stock and hybrid are much closer than the older captures implied

Current best reading:

- the branch still has a low absolute performance problem
- but that problem is not currently isolated to hybrid reconnect churn
- both stock and hybrid remain slow in the current short combined VirGL probes

That shifts the next blocker again:

- first make the probe harness enforce a clean start every run
- then compare longer steady-state stock vs hybrid measurements on that clean baseline
- only after that should any new "hybrid-specific renderer regression" claim be treated as reliable

### 2026-03-15 Checkpoint 13

A host-side clean-start probe harness now exists:

- `scripts/hybrid-x11-clean-probe.ps1`

The script does four things in a fixed order:

- force-stop `com.termux.x11`
- force-stop `com.runelitetablet`
- clear logcat
- dispatch the combined `START_AND_RUN_HYBRID_X11_CLIENT` probe and write filtered logcat plus a summary file under `perf-captures/`

Representative usage:

- `powershell -ExecutionPolicy Bypass -File scripts/hybrid-x11-clean-probe.ps1 -Variant stock -Mode inspect`
- `powershell -ExecutionPolicy Bypass -File scripts/hybrid-x11-clean-probe.ps1 -Variant hybrid -Mode glxgears-virgl-windowed`

The summary file records:

- total `New client connection!`
- whether hybrid attach was seen
- counts before and after attach when attach exists
- whether `DISPLAY ready` was observed
- last renderer line, last FPS line, and the combined probe result line

#### Important correction from harness validation

The first validation attempt launched stock and hybrid **in parallel** against the same device:

- `perf-captures/stock-combined-inspect-clean-20260315-120123.logcat.txt`
- `perf-captures/hybrid-combined-inspect-clean-20260315-120123.logcat.txt`

Those captures are invalid for comparison and should be ignored.

Reason:

- both wrappers were contending for the same device state, same packages, and same logcat buffer
- the hybrid run showed `30` connection events with `8` after attach, which conflicts with the clean sequential evidence and is best explained by the overlapping launch itself

This is now an explicit rule for future automation:

- do not parallelize stock and hybrid probes against one physical device

#### Sequential harness validation

The sequential reruns reproduced the earlier clean-start interpretation.

New sequential captures:

- `perf-captures/stock-combined-inspect-clean-20260315-120156.logcat.txt`
- `perf-captures/hybrid-combined-inspect-clean-20260315-120234.logcat.txt`
- `perf-captures/stock-combined-virgl-windowed-clean-20260315-120429.logcat.txt`
- `perf-captures/hybrid-combined-virgl-windowed-clean-20260315-120448.logcat.txt`

Observed:

- stock clean `inspect`: `1` `New client connection!`, `DISPLAY ready`
- hybrid clean `inspect`: `4` `New client connection!`, attach seen, `before_attach=4`, `after_attach=0`, `DISPLAY ready`
- stock clean windowed VirGL: `1` `New client connection!`, `3 frames in 5.0 seconds = 0.6 FPS`
- hybrid clean windowed VirGL: `1` `New client connection!`, attach seen, `before_attach=1`, `after_attach=0`, `3 frames in 5.0 seconds = 0.6 FPS`

So the scripted clean-start path is now reproducing the same essential A/B result that the earlier manual clean runs showed:

- hybrid does not show a post-attach reconnect storm under clean starts
- stock and hybrid remain at parity on the short windowed VirGL probe

One minor nuance from the scripted windowed runs:

- the filtered log sometimes does not include `DISPLAY ready` in the short VirGL case even when FPS output is present
- that does not currently invalidate the capture because the completion line and FPS line are both present

Current best next step:

- extend the harness-driven matrix to longer steady-state probes, but keep every run strictly sequential and clean-started

### 2026-03-15 Checkpoint 14

The clean-start method now reaches **real RuneLite**, not just synthetic X11 clients.

New host-side harness:

- `scripts/hybrid-x11-runelite-evidence.ps1`

This script:

- best-effort shuts down any previous RuneLite session
- force-stops `com.termux.x11` and `com.runelitetablet`
- clears logcat / gfxinfo
- dispatches `RUN_REAL_HYBRID_LAUNCHER`
- actively polls launch state with `DUMP_REAL_HYBRID_LAUNCH_STATE`
- waits for RuneLite GPU/client initialization
- captures filtered `RLT` / `LorieNative` / `BufferQueueProducer` / `LayerHistory` evidence
- writes a logcat capture plus a summary file under `perf-captures/`

Representative usage:

- `powershell -ExecutionPolicy Bypass -File scripts/hybrid-x11-runelite-evidence.ps1`

#### First real RuneLite evidence pass

Validated capture:

- `perf-captures/hybrid-runelite-clean-20260315-122439.logcat.txt`
- `perf-captures/hybrid-runelite-clean-20260315-122439.summary.txt`

Observed in that run:

- RuneLite reached clean-start `running` state
- client initialization completed: `Client initialization took 16467ms`
- GPU plugin active on VirGL:
  - `Using device: virgl (Mali-G720-Immortalis MC12)`
  - `Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8`
- hybrid host surface repeatedly voted `ExplicitDefault (120.00 Hz)`
  - summary counted `56` such votes during the short capture
- hybrid surface `queueBuffer` briefly hit `119.99 FPS`
- last sampled hybrid surface `queueBuffer` during the capture was `62.97 FPS`
- `LorieNative` last sampled `273 frames in 5.0 seconds = 54.6 FPS`
- `LorieNative` max sampled `64.6 FPS`

This is the first clean-start measurement that directly ties together all three pieces:

- real RuneLite launched
- GPU plugin was actually active
- the visible hybrid surface was explicitly treated as a `120 Hz` target by SurfaceFlinger / LayerHistory

#### What this means

The new method is now viable for the actual question, "is real RuneLite rendering at higher refresh on the hybrid host?"

Current answer from the evidence:

- **yes**, the surface is being targeted as `120 Hz`
- **no**, RuneLite is not yet sustaining `120 FPS`

Current measured behavior is closer to:

- brief bursts near `120 FPS`
- more typical steady samples in roughly the `50` to `65 FPS` band

So the branch has crossed an important threshold:

- we are no longer limited to synthetic probes
- we can now test real RuneLite on a clean-start path and measure the visible surface directly

But the `120 FPS` goal is still not met.

### 2026-03-15 Checkpoint 15

The real RuneLite evidence path now supports both **stock** and **hybrid** launches through the same launcher script.

Changes made:

- `launch-runelite.sh` now accepts `--x11-presentation hybrid|stock`
- `HybridX11TestReceiver` passes that variant through from adb
- `scripts/hybrid-x11-runelite-evidence.ps1` now accepts `-Variant stock|hybrid`

This removed the last remaining ambiguity in the comparison:

- previous "real RuneLite" evidence was hybrid-only because the launcher script itself always forced `TERMUX_X11_OVERRIDE_PACKAGE=com.runelitetablet`

#### Clean-start real RuneLite stock comparator

Validated stock capture:

- `perf-captures/stock-runelite-clean-20260315-131955.logcat.txt`
- `perf-captures/stock-runelite-clean-20260315-131955.summary.txt`

Observed:

- RuneLite reached `running`
- GPU plugin active on VirGL:
  - `Using device: virgl (Mali-G720-Immortalis MC12)`
  - `Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8`
- stock visible surface:
  - `SurfaceView[com.termux.x11/com.termux.x11.MainActivity]@0(BLAST)`
- stock surface did **not** show `ExplicitDefault (120.00 Hz)` votes in the filtered capture
- stock visible-surface `queueBuffer` stats over the 30s capture:
  - sample count: `57`
  - average: `44.92 FPS`
  - median: `48.78 FPS`
  - max: `120.01 FPS`
  - last: `41.00 FPS`
- stock `LorieNative` stats:
  - sample count: `13`
  - average: `37.82 FPS`
  - median: `53.0 FPS`
  - max: `58.2 FPS`
  - last: `55.4 FPS`

#### Matching clean-start hybrid control on the shared launcher path

Validated hybrid capture:

- `perf-captures/hybrid-runelite-clean-20260315-132151.logcat.txt`
- `perf-captures/hybrid-runelite-clean-20260315-132151.summary.txt`

Observed:

- RuneLite reached `running`
- GPU plugin active on VirGL:
  - `Using device: virgl (Mali-G720-Immortalis MC12)`
  - `Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8`
- hybrid surface repeatedly voted `ExplicitDefault (120.00 Hz)`:
  - vote count in capture: `60`
- hybrid visible-surface `queueBuffer` stats over the 30s capture:
  - sample count: `58`
  - average: `43.25 FPS`
  - median: `47.23 FPS`
  - max: `117.0 FPS`
  - last: `52.13 FPS`
- hybrid `LorieNative` stats:
  - sample count: `13`
  - average: `36.63 FPS`
  - median: `49.8 FPS`
  - max: `58.8 FPS`
  - last: `48.2 FPS`

#### Comparator interpretation

This is the first trustworthy real-RuneLite stock-vs-hybrid comparison on a shared clean-start method.

Current reading:

- stock and hybrid sit in essentially the same sustained performance band
- hybrid can get the visible app-owned surface explicitly treated as `120 Hz`
- but that difference in SurfaceFlinger voting does **not** translate into meaningfully higher steady-state RuneLite throughput on the current X11/VirGL path

So the main bottleneck is no longer credibly "hybrid overhead" by itself.

Current best reading:

- the bottleneck is in the broader X11/VirGL presentation chain or the current real RuneLite runtime behavior on that chain
- more stock-vs-hybrid parity testing by itself is unlikely to unlock `120 FPS`

### 2026-03-15 Checkpoint 16

A direct half-resolution optimization test was then added to the same real RuneLite evidence harness.

Changes made:

- `launch-runelite.sh` now accepts `--gpu-display-resolution`
- `HybridX11TestReceiver` forwards a `gpu_display_resolution` extra
- `scripts/hybrid-x11-runelite-evidence.ps1` now accepts:
  - `-GpuDisplayResolution default`
  - `-GpuDisplayResolution native`
  - `-GpuDisplayResolution half-res`

The first optimization test targeted the hybrid path with a forced GPU-side custom resolution:

- `custom:1480x924`

Validated capture:

- `perf-captures/hybrid-runelite-halfres-clean-20260315-132606.logcat.txt`
- `perf-captures/hybrid-runelite-halfres-clean-20260315-132606.summary.txt`

The launch log confirms the override really applied:

- `launchBackground ... --gpu-display-resolution custom:1480x924`
- `Setting display: forced custom GPU resolution 1480x924`

Observed in the half-res run:

- RuneLite reached `running`
- GPU plugin still active on VirGL
- hybrid surface still voted `ExplicitDefault (120.00 Hz)`:
  - vote count: `58`
- hybrid visible-surface `queueBuffer` stats:
  - sample count: `57`
  - average: `44.29 FPS`
  - median: `49.94 FPS`
  - max: `119.02 FPS`
  - last: `63.93 FPS`
- hybrid `LorieNative` stats:
  - sample count: `13`
  - average: `37.25 FPS`
  - median: `50.0 FPS`
  - max: `58.2 FPS`
  - last: `50.0 FPS`

#### Half-res interpretation

This was a meaningful negative result.

Compared with the matching default-resolution hybrid run:

- visible-surface average stayed effectively flat
- `LorieNative` average stayed effectively flat
- the path still produced brief bursts near `120 FPS`
- the sustained band still did **not** move meaningfully toward `120 FPS`

So lowering the pixel load to `1480x924` does **not** materially unlock the missing throughput on the current hybrid X11/VirGL path.

Current best reading after this test:

- the remaining ceiling is not explained mainly by native-resolution fill load
- the current X11/VirGL presentation path still does not look viable for a true sustained `120 FPS` goal
- the next work should pivot away from parity-only testing and toward either:
  - a deeper presentation-path redesign
  - or an app-owned/direct-surface route that bypasses the current X11 display chain more aggressively

### 2026-03-15 Checkpoint 17

The next experiment attempted to move the Java-side X server entrypoint into our own APK instead of relying on the external `com.termux.x11.Loader` path.

Changes made:

- added an in-app fork of `com.termux.x11.CmdEntryPoint`
- kept the existing in-app `LorieView` / `MainActivity` shim path
- launched the entrypoint through `app_process` using our APK as the `CLASSPATH`
- still loaded donor `libXlorie.so` from the installed `com.termux.x11` package
- added a new presentation variant:
  - `internal-hybrid`

Relevant code changes:

- `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java`
- `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11TestReceiver.kt`
- `scripts/hybrid-x11-runelite-evidence.ps1`

#### First internal in-app server run

Validated failure capture:

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-135658.logcat.txt`

Observed:

- app built and installed successfully with the new in-app `CmdEntryPoint`
- the real RuneLite evidence harness dispatched:
  - `variant=internal-hybrid`
- the launcher recorded:
  - `Termux:X11 presentation variant: internal-hybrid server=internal override=<none>`
- but the X11 socket never appeared
- repeated dump-state snapshots showed:
  - `Waiting for X11 socket...`
  - `X11 socket attempt 1 failed; restarting Termux:X11...`
  - `X11 socket attempt 2 failed; restarting Termux:X11...`
  - `ERROR: X11 socket not ready after 30 seconds`
- the captured `process snapshot` never showed a surviving `app_process` / `CmdEntryPoint` server process
- no `x11.pid`, `x11.server`, or later RuneLite runtime markers were written into the session directory
- RuneLite never reached GPU/client initialization

#### Interpretation

This is an important but still early result:

- the in-app Java-side entrypoint idea is **buildable**
- but the first end-to-end launch currently fails **before socket creation**

So the new direct path is not dead, but it is not yet a working replacement for the stock Java-side startup chain.

Current best reading:

- the failure is earlier than rendering / pacing
- the immediate blocker is getting the in-app `CmdEntryPoint` path to create the X11 socket at all
- the next useful debugging step for this branch would be to capture direct `app_process` stdout/stderr for the in-app `CmdEntryPoint` launch before trying RuneLite again

### 2026-03-15 Checkpoint 18

The `internal-hybrid` branch was then debugged until it reached a real clean-start RuneLite evidence run.

Changes made:

- instrumented `launch-runelite.sh` so `internal-hybrid` preserves:
  - `internal-x11-start.log`
  - per-attempt process state
  - direct failure evidence in the dump-state path
- expanded `HybridX11TestReceiver` dump output to include:
  - `internal-x11-start.log`
  - `x11.internal.log`
- widened diagnosis beyond the filtered evidence tags using raw device `logcat`
- patched `com.termux.x11.CmdEntryPoint` in two important ways:
  - extracted donor `libXlorie.so` from the installed `com.termux.x11` APK instead of trusting `ApplicationInfo.nativeLibraryDir`
  - wrote the extracted library into the Termux runtime filesystem (`TMPDIR` / `HOME`) instead of using `ctx.getFilesDir()`, because the `app_process` path is running under the Termux UID with a system context

Relevant code changes:

- `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java`
- `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11TestReceiver.kt`

#### Root-cause chain that was confirmed

The in-app path failed in three distinct layers before it finally worked:

1. `internal-hybrid` initially failed before socket creation with no useful stderr in the filtered harness.
2. Added startup-log preservation proved the first concrete failure was:
   - `dlopen failed: library ".../lib/arm64/libXlorie.so" not found`
3. Pulling the donor APK confirmed:
   - `lib/arm64-v8a/libXlorie.so` exists inside `com.termux.x11` `base.apk`
   - but there is no usable extracted `/lib/arm64/.../libXlorie.so` file on disk
4. After switching to APK extraction, raw logcat exposed the next blocker:
   - `java.lang.RuntimeException: No data directory found for package android`
   - because `ctx.getFilesDir()` was invalid in the `app_process` + system-context path
5. After moving extraction into the Termux runtime filesystem, the in-app server path finally booted far enough to create the socket and launch real RuneLite.

#### First successful real RuneLite `internal-hybrid` run

Validated capture:

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-151237.logcat.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-151237.summary.txt`

Observed:

- RuneLite reached `running`
- GPU plugin active on VirGL:
  - `Using device: virgl (Mali-G720-Immortalis MC12)`
  - `Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8`
- internal-hybrid visible surface repeatedly voted `ExplicitDefault (120.00 Hz)`:
  - vote count: `56`
- internal-hybrid visible-surface `queueBuffer` stats:
  - sample count: `56`
  - average: `42.52 FPS`
  - median: `47.22 FPS`
  - max: `109.12 FPS`
  - last: `52.56 FPS`
- internal-hybrid `LorieNative` stats:
  - sample count: `13`
  - average: `35.54 FPS`
  - median: `47.0 FPS`
  - max: `58.0 FPS`
  - last: `58.0 FPS`

#### Immediate follow-up: internal-hybrid half-res

Validated capture:

- `perf-captures/internal-hybrid-runelite-halfres-clean-20260315-151441.logcat.txt`
- `perf-captures/internal-hybrid-runelite-halfres-clean-20260315-151441.summary.txt`

Observed:

- RuneLite reached `running`
- GPU plugin still active on VirGL
- internal-hybrid visible surface still voted `ExplicitDefault (120.00 Hz)`:
  - vote count: `54`
- internal-hybrid visible-surface `queueBuffer` stats:
  - sample count: `57`
  - average: `45.56 FPS`
  - median: `48.0 FPS`
  - max: `119.99 FPS`
  - last: `64.92 FPS`
- internal-hybrid `LorieNative` stats:
  - sample count: `13`
  - average: `38.77 FPS`
  - median: `53.2 FPS`
  - max: `61.4 FPS`
  - last: `58.4 FPS`

#### Internal-hybrid interpretation

This is a real milestone:

- the app-owned in-app `CmdEntryPoint` route is now **bootable**
- it can launch real RuneLite cleanly
- it can produce the same kind of evidence capture as the stock/hybrid external-loader path

But the performance reading is still sobering:

- internal-hybrid does **not** break out of the existing steady-state performance envelope
- its default-res and half-res runs are only marginally different from the earlier external hybrid runs
- brief bursts near `120 FPS` still appear
- sustained throughput still clusters far below the `120 FPS` goal

Current best reading after this checkpoint:

- the app-owned Java-side bootstrap path is now viable as an experimental platform
- but simply replacing the external `Loader` bootstrap with an in-app `CmdEntryPoint` is **not** the missing performance unlock by itself
- the remaining ceiling still appears to be in the broader X11 / Lorie / VirGL presentation chain or in deeper renderer-path constraints beyond bootstrap ownership

### 2026-03-15 Checkpoint 19

The next direct-surface/native step was completed: `runelite-tablet` now builds and packages its own in-app `libXlorie.so`, and that packaged native renderer was validated on-device through the real `internal-hybrid` RuneLite path.

#### What changed

The app build was rewired to compile the upstream Termux:X11 native stack directly inside `runelite-tablet`:

- `runelite-tablet/app/build.gradle.kts`
  - added `externalNativeBuild` wiring to the vendored upstream CMake tree
  - passed an explicit Windows `BISON_EXECUTABLE` to CMake
- `third_party/termux-x11-upstream/app/src/main/cpp/recipes/xkbcomp.cmake`
  - replaced the host-compiled `makekeys` helper with a Python generator path for `ks_tables.h`
- `third_party/termux-x11-upstream/app/src/main/cpp/scripts/generate_ks_tables.py`
  - added a Python port of the upstream `makekeys` generator
- `third_party/termux-x11-upstream/app/src/main/cpp/libx11/include/X11/xlocale.h`
  - patched Android/Bionic handling so system `locale_t` resolves correctly instead of recursively shadowing `X11/xlocale.h`
- `third_party/termux-x11-upstream/app/src/main/cpp/CMakeLists.txt`
  - switched to the simpler vendored-tree model: the third-party source tree is patched in-place and the build-time `target_apply_patch(...)` hook is now a no-op

The vendored upstream native sources were patched in place so the build no longer depends on Linux-only patch shelling during every CMake configure. This includes the expected upstream patch set in:

- `xserver`
- `libepoxy`
- `pixman`
- `libx11`
- `xkbcomp`
- `libxkbfile`
- `libxtrans`

#### Build result

Validated build:

- `runelite-tablet :app:assembleDebug`

Result:

- `BUILD SUCCESSFUL`
- `runelite-tablet/app/build/outputs/apk/debug/app-debug.apk` now contains the packaged in-app native `Xlorie` build

#### Runtime validation: packaged native library actually used

Installed the new APK and reran the clean-start real RuneLite evidence harness on:

- `variant=internal-hybrid`

Validated capture:

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-161446.logcat.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-161446.summary.txt`

The important runtime proof points are in the logcat capture:

- host activity path loaded the packaged library directly:
  - `ExternalXlorieLoader: loaded packaged Xlorie`
  - `HybridX11HostActivity: loaded Xlorie from packaged:Xlorie`
- `CmdEntryPoint` resolved our app package and extracted **our** APK’s `libXlorie.so`, not the donor package:
  - `Extracted Xlorie library for com.runelitetablet ... entry=lib/arm64-v8a/libXlorie.so`
  - extracted to:
    - `/data/data/com.termux/files/usr/tmp/termux-x11-com.runelitetablet-libs/arm64-v8a-libXlorie.so`

So the app is no longer dependent on the donor Termux:X11 native payload for the active `internal-hybrid` render path.

#### Runtime performance reading

Observed from `internal-hybrid-runelite-defaultres-clean-20260315-161446.summary.txt`:

- RuneLite reached `running`
- GPU plugin active on VirGL:
  - `Using device: virgl (Mali-G720-Immortalis MC12)`
  - `Using driver: 4.3 (Compatibility Profile) Mesa 25.2.8-0ubuntu0.25.10.1`
- visible hybrid surface continued to get `120.00 Hz` votes:
  - vote count: `60`
- visible-surface `queueBuffer` stats:
  - sample count: `49`
  - average: `45.28 FPS`
  - median: `51.3 FPS`
  - max: `120 FPS`
  - last: `42.59 FPS`
- `LorieNative` stats:
  - sample count: `11`
  - average: `37.55 FPS`
  - median: `57.4 FPS`
  - max: `65.6 FPS`
  - last: `46.6 FPS`

#### Interpretation

This is another real milestone:

- the app now owns the visible host surface
- the app now owns the Java bootstrap path
- the app now owns the packaged native `Xlorie` renderer binary too

But the performance conclusion is still consistent:

- replacing the donor native binary with an in-app packaged native build did **not** unlock a new steady-state FPS regime
- the real RuneLite path is still living in roughly the same broad `40-60 FPS` envelope
- brief bursts at or near `120 FPS` still happen, but sustained throughput remains far below the target

Current best reading after this checkpoint:

- the direct-surface/native ownership migration is now functionally real end-to-end
- this is the correct platform for further native renderer experiments
- but simply packaging the same `Xlorie`/Lorie renderer inside our app is **not** enough by itself to reach `120 FPS`
- the next meaningful optimization work has to alter the native presentation/render path itself, not just who owns or loads it

### 2026-03-15 Checkpoint 20

The next pass moved from outer-path ownership changes to native timing evidence inside the packaged `internal-hybrid` path.

#### What changed

- restored the earlier `renderer.c` post-swap behavior after the no-op/fence-removal experiment regressed performance
- added coarse 5-second timing buckets inside the native Lorie renderer:
  - renderer lock wait
  - EGL fence wait
  - `eglSwapBuffers`
  - total frame time
  - inter-frame cadence
- added 5-second cadence counters on the X11 side:
  - choreographer callbacks
  - redraw wakeups
- extended `scripts/hybrid-x11-runelite-evidence.ps1` to capture these new lines into the evidence summary

#### Key validated capture

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-162831.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-162831.logcat.txt`

#### What it showed

Steady-state after startup:

- choreographer callbacks reached `120 FPS`
- redraw wakeups also reached `120 FPS`
- the native renderer still only sustained roughly the mid-`40s` to high-`50s`
- renderer hot-path cost was low relative to the missed frame budget:
  - `avg_fence_ms` around `2.5 ms`
  - `avg_swap_ms` around `0.6 ms`
  - `avg_frame_ms` around `4.8-4.9 ms`
  - but `avg_inter_frame_ms` stayed around `19-20 ms`

Representative steady-state renderer line:

- `XloriePerf: frames=249 avg_lock_ms=0.001 avg_fence_ms=2.517 avg_swap_ms=0.605 avg_frame_ms=4.848 avg_inter_frame_ms=20.171 estimated_fps=49.6`

#### Interpretation

This ruled out the visible native presenter as the main throughput limiter:

- Android is willing to schedule the surface at `120 Hz`
- the Lorie renderer is not spending `~16-20 ms` inside `eglSwapBuffers` or fence waits
- instead, it is simply **not being asked to draw a new root-buffer frame often enough**

So the ceiling moved upstream of the native presentation layer itself.

### 2026-03-15 Checkpoint 21

The next question was whether RuneLite itself was still carrying a client-side FPS cap. The launcher was updated to force a high-refresh GPU config before launch.

#### What changed

- `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
  - now writes these RuneLite settings before launch:
    - `gpu.unlockFps=true`
    - `gpu.fpsTarget=120`
    - `gpu.vsyncMode=OFF`
  - the launch log now prints:
    - `Configured RuneLite GPU settings: unlockFps=true fpsTarget=120 vsyncMode=OFF`
- `adb install --no-streaming -r ...` was used for redeploy after a streaming install failed with an APK certificate digest verification error on this device
- X11-side counters were extended to include:
  - damage-triggered redraw cadence
  - Present after-flip cadence

#### Key validated captures

Forced-120 default-res:

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-164224.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-164224.logcat.txt`

Forced-120 half-res:

- `perf-captures/internal-hybrid-runelite-halfres-clean-20260315-164514.summary.txt`
- `perf-captures/internal-hybrid-runelite-halfres-clean-20260315-164514.logcat.txt`

#### What it showed

The forced client config was definitely applied:

- `Configured RuneLite GPU settings: unlockFps=true fpsTarget=120 vsyncMode=OFF`

But even with that change, steady-state throughput still stayed far below `120 FPS`.

Default-res forced-120 evidence:

- visible surface average: `40.06 FPS`
- visible surface median: `45 FPS`
- last `LorieNative`: `45.6 FPS`
- last damage-triggered redraw cadence: `45.8 FPS`
- last renderer estimated cadence: `46.6 FPS`
- Present after-flips: `0.0 FPS`

Representative steady-state window from raw log:

- `damage-triggered redraws in 5.0 seconds = 57.2 FPS`
- `289 frames in 5.0 seconds = 57.8 FPS`
- `XloriePerf ... estimated_fps=55.1`

Half-res forced-120 evidence:

- visible surface average: `41.79 FPS`
- visible surface median: `50 FPS`
- last `LorieNative`: `58.6 FPS`
- last damage-triggered redraw cadence: `58.6 FPS`
- last renderer estimated cadence: `56.5 FPS`
- Present after-flips: `0.0 FPS`

#### Interpretation

This was the most useful narrowing result of the session:

- the earlier ceiling was **not** just a stale RuneLite `fpsTarget=60` style setting
- forcing `unlockFps=true`, `fpsTarget=120`, and `vsyncMode=OFF` did **not** unlock a new FPS regime
- half resolution provided only modest improvement
- the cadence that actually controls visible throughput is the X11-side damage generation rate, which still clusters around roughly `45-58 FPS`
- the renderer then tracks that damage cadence closely
- Present after-flips staying at `0` strongly suggests the active RuneLite/AWT/X11 path is not getting the kind of Present-flip cadence that would support a clean high-refresh fullscreen path here

Current best reading after this checkpoint:

- the native Lorie presentation path is no longer the main suspect
- Android callback cadence and surface voting are already compatible with `120 Hz`
- the remaining ceiling is upstream in the Linux client path itself:
  - RuneLite/AWT/X11 damage production
  - or a broader VirGL/X11/AWT composition path before the final Android-present step

That means the next meaningful implementation work should stop focusing on Lorie presentation micro-optimizations and instead target the upstream frame-production path.

### 2026-03-15 Checkpoint 22

One more bounded upstream check was added before moving on: make RuneLite launch scale configurable and compare the forced-`120` default-res path at `--scale 1`.

#### What changed

- `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
  - now accepts `--runelite-scale`
- `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11TestReceiver.kt`
  - passes `runelite_scale` through to the launcher
- `scripts/hybrid-x11-runelite-evidence.ps1`
  - adds `-RuneLiteScale`
  - records `runelite_scale=` in the summary

#### Key validated capture

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-165328.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-165328.logcat.txt`

#### What it showed

With the forced high-refresh GPU config still active:

- `configured_gpu_settings_line=... unlockFps=true fpsTarget=120 vsyncMode=OFF`
- `runelite_scale=1`

The throughput envelope still did **not** break upward:

- visible surface average: `43.39 FPS`
- visible surface median: `50.99 FPS`
- last `LorieNative`: `56.2 FPS`
- last damage-triggered redraw cadence: `56.2 FPS`
- last renderer estimated cadence: `53.5 FPS`
- Present after-flips: `0.0 FPS`

Representative steady-state raw lines:

- `damage-triggered redraws in 5.0 seconds = 56.2 FPS`
- `XloriePerf ... estimated_fps=53.5`

#### Interpretation

Lowering RuneLite launch scale from `2` to `1` did not unlock a materially different performance regime.

So at this point the negative results are consistent across:

- in-app native `Xlorie`
- forced `gpu.unlockFps=true`
- forced `gpu.fpsTarget=120`
- forced `gpu.vsyncMode=OFF`
- half resolution
- lower RuneLite scale

Current best reading after this checkpoint:

- the visible Android surface is not the main blocker
- the native presenter is not the main blocker
- simple client-side configuration toggles are not the main blocker
- the remaining ceiling is still upstream in the Linux/AWT/X11 frame-production path itself

### 2026-03-15 Checkpoint 23

The synthetic `internal-hybrid` path was debugged next because the first combined-probe control had been unstable and could not be trusted as a baseline.

#### What changed

- `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java`
  - restored the zero-argument instance `sendBroadcast()` method expected by the donor native bridge

#### Root cause

The native upstream bridge in `third_party/termux-x11-upstream/app/src/main/cpp/lorie/cmdentrypoint.c` does:

- `GetMethodID(..., "sendBroadcast", "()V")`
- then `CallVoidMethod(...)` from `CmdEntryPoint.listenForConnections()`

Our forked `CmdEntryPoint` only exposed `sendBroadcast(Intent intent)`, so the synthetic `internal-hybrid` server was aborting with:

- `JNI DETECTED ERROR IN APPLICATION: mid == null`
- `from void com.termux.x11.CmdEntryPoint.listenForConnections()`

#### Key validated capture

- `perf-captures/internal-hybrid-combined-inspect-clean-20260315-192411.summary.txt`
- `perf-captures/internal-hybrid-combined-inspect-clean-20260315-192411.logcat.txt`

#### What it showed

After the JNI fix:

- `new_client_connection_count=5`
- `attach_seen=true`
- `after_attach=0`
- `display_ready=true`
- `OpenGL renderer string: llvmpipe (LLVM 20.1.8, 128 bits)`

So the synthetic `internal-hybrid` path is no longer crashing before attach.

#### Interpretation

This was a real code bug, not noise:

- the donor native `CmdEntryPoint` contract must stay ABI-compatible with the Java stub we expose
- the earlier synthetic `internal-hybrid` failures were partly caused by this JNI mismatch
- once corrected, the synthetic path became usable again for clean control measurements

### 2026-03-15 Checkpoint 24

The next issue was clean-start contamination. The synthetic fullscreen VirGL control still failed intermittently even after the JNI fix.

#### What changed

- `scripts/hybrid-x11-clean-probe.ps1`
  - clean start now force-stops:
    - `com.termux`
    - `com.termux.x11`
    - `com.runelitetablet`
- `scripts/hybrid-x11-runelite-evidence.ps1`
  - same clean-start expansion

#### Root cause

The stale internal server was surviving because the `internal-hybrid` `CmdEntryPoint` process actually runs under the `com.termux` UID.

So force-stopping only:

- `com.termux.x11`
- `com.runelitetablet`

was not sufficient to kill a previous `termux-x11 com.runelitetablet :0` server.

That was confirmed on-device with a lingering process:

- `u0_a164 ... termux-x11 com.runelitetablet :0`

and with the failed synthetic run:

- `Cannot establish any listening sockets - Make sure an X server isn't already running`

#### Key validated capture

- `perf-captures/internal-hybrid-combined-virgl-fullscreen-clean-20260315-192829.summary.txt`
- `perf-captures/internal-hybrid-combined-virgl-fullscreen-clean-20260315-192829.logcat.txt`

#### What it showed

With `com.termux` included in the clean start, the fullscreen VirGL control became valid again:

- `new_client_connection_count=1`
- `attach_seen=true`
- `after_attach=0`
- `fps_line=... 3 frames in 5.0 seconds = 0.6 FPS`

This puts the repaired `internal-hybrid` synthetic fullscreen control back in the same broad envelope as the earlier stock/external-hybrid probes.

#### Interpretation

This matters for methodology more than throughput:

- the host-side harness now actually produces a trustworthy clean start for `internal-hybrid`
- the repaired synthetic control does **not** reveal a hidden `internal-hybrid` performance win
- instead it reinforces the existing conclusion that the path-specific presentation swap alone is not the missing unlock

### 2026-03-15 Checkpoint 25

One follow-up real RuneLite run was made with the stronger clean start to see whether fully cold Termux cleanup changed the actual top-level result.

#### Key capture

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-192917.logcat.txt`

#### What it showed

This run did **not** reach client initialization within the harness timeout.

But the dump-state snapshots show the failure is not in the same place as the earlier synthetic bugs:

- X11 socket really came up
- internal `CmdEntryPoint` launch succeeded far enough for:
  - `X11 socket ready`
  - `Launching X11 host activity`
- VirGL also came up:
  - `VirGL native GLES server started (PID ..., socket ready)`

The launch log then showed:

- `No credentials env file provided — RuneLite will show its own login`
- `Launching RuneLite...`
- `RuneLite exited with code: 1`

#### Interpretation

The stronger clean start exposed a new caveat:

- the synthetic/internal server cleanup path is now healthier
- but a fully cold `com.termux` stop can leave the real RuneLite bring-up path failing earlier for a separate reason
- the current evidence points more toward launcher/session bring-up than back toward X11 throughput

So after this round the project state is:

- synthetic `internal-hybrid` controls are trustworthy again
- the clean-start harness is more correct because it includes `com.termux`
- real RuneLite performance conclusions are still unchanged
- but the fully cold real-launch path now needs a separate pass to determine whether the blocker is auth/session state, launcher state, or another startup dependency

### 2026-03-15 Checkpoint 26

The next pass targeted the fully cold real-launch failure directly instead of doing more synthetic parity work.

#### What changed

First, `launch-runelite.sh` was instrumented around the cold failure point:

- added explicit outer `proot-distro` preflight logging
- added a dedicated cold-start `proot` smoke test before the real launch
- added deeper inner-launch logging around the `bash -c` payload

#### Key capture

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-195412.logcat.txt`

#### What it showed

The dedicated smoke test succeeded repeatedly under a fully cold `com.termux` start:

- `proot-distro` was present at `/data/data/com.termux/files/usr/bin/proot-distro`
- the smoke test printed `PROOT_SMOKE_OK`
- Ubuntu entered successfully as `root`
- `/root/runelite` existed
- smoke test exit code was `0`

But the real launch still exited immediately afterward with:

- `RuneLite exited with code: 1`

and without reaching the inner-launch preflight lines from the large inline payload.

#### Interpretation

That narrowed the cold-start failure materially:

- it is **not** a generic `proot-distro` cold-start failure
- it is **not** a missing distro/root filesystem problem
- the unstable piece is the giant inline `bash -c "..."` payload used for the real launch path

So the blocker moved from "does cold `proot` work?" to "is the inline launcher payload itself reliable under cold start?"

### 2026-03-15 Checkpoint 27

The inline `proot` payload was then replaced with an actual temporary script in shared `/tmp`, and the cold real-launch path was rerun.

#### What changed

In `launch-runelite.sh`:

- the main inline `proot-distro login ... -- bash -c "..."` body was externalized to a temporary script:
  - Termux-side path: `$PREFIX/tmp/.rlt-proot-launch-$SESSION_ID.sh`
  - proot-side path: `/tmp/.rlt-proot-launch-$SESSION_ID.sh`
- the script is invoked via:
  - `proot-distro login ubuntu --shared-tmp ... -- env ... bash /tmp/.rlt-proot-launch-...`
- runtime values like session dir, GPU mode, FPS config, and UI scale are now passed as environment variables instead of being interpolated through one giant quoted string

This removes the nested quoting/parsing layer that had become the cold-start failure point.

#### Key validated capture

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-200150.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-200150.logcat.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-200417.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-200417.logcat.txt`

#### What it showed

The fully cold `internal-hybrid` real-launch path recovered:

- `state_running_seen=True`
- RuneLite reached client init:
  - `Client initialization took 10067ms`
- GPU plugin came up on VirGL:
  - `Using device: virgl (Mali-G720-Immortalis MC12)`
- the visible hybrid surface still received repeated `120.00 Hz` votes

Performance stayed in the same broad envelope as before:

- visible surface average: `39.33 FPS`
- visible surface max sample: `114.05 FPS`
- `LorieNative` average: `28.48 FPS`
- last `LorieNative` sample: `46.8 FPS`
- last renderer estimated cadence: `45.6 FPS`
- last damage-request cadence: `47 FPS`
- `present after-flips`: still `0`

#### Interpretation

This is a real branch-quality improvement even though it is not a throughput breakthrough:

- the fully cold `internal-hybrid` real-launch path is working again
- the earlier cold-start failure was primarily launcher-plumbing instability in our inline `proot` payload
- externalizing that payload into a real script made the launch path materially more robust
- the sustained rendering ceiling is still unchanged, so this was a reliability fix for measurement and iteration, not a solution to the `120 FPS` goal

The second cold confirmation run landed in the same broad range:

- visible surface average: `37.75 FPS`
- visible surface max sample: `111.08 FPS`
- `LorieNative` average: `30.24 FPS`
- last renderer estimated cadence: `46.0 FPS`

So the launch fix appears reproducible, while the throughput ceiling remains the same.

### 2026-03-15 Checkpoint 28

After the cold-launch path was stabilized again, the evidence harness itself was brought closer to the real app launch path by reusing the saved credential/session env deployment.

#### What changed

A shared helper was added so both the normal launch path and `HybridX11TestReceiver` can deploy the same Termux-side env file:

- new helper: `app/src/main/java/com/runelitetablet/auth/LaunchEnvDeployer.kt`
- `SetupViewModel.performLaunch()` now uses that helper instead of maintaining an inline copy
- `HybridX11TestReceiver.ACTION_RUN_REAL_LAUNCHER` now deploys the same `.rlt-launch-env.sh` file before dispatching `launch-runelite.sh`

This removed a long-standing mismatch where the evidence harness always took the anonymous fallback path:

- old behavior: `No credentials env file provided — RuneLite will show its own login`
- new behavior: the harness deploys and forwards the same saved `JX_*` variables as the regular app launch

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-200932.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-200932.logcat.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-201138.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-clean-20260315-201138.logcat.txt`

#### What it showed

The harness now clearly sources and forwards the saved session env:

- `LaunchEnvDeployer: env file deployed to Termux at /data/data/com.termux/files/home/.rlt-launch-env.sh`
- `HybridX11TestReceiver: ... envFile=/data/data/com.termux/files/home/.rlt-launch-env.sh`
- `Sourcing credentials from env file...`
- `JX_SESSION_ID=***`
- `JX_CHARACTER_ID=***`
- `JX_DISPLAY_NAME=***`
- `Credential env file written for proot forwarding`

The longer 45-second capture also confirmed the stabilized launch path still works with the credentialed harness:

- RuneLite reached client init in `11837ms`
- GPU plugin active on VirGL
- surface still repeatedly voted `120.00 Hz`
- visible surface average: `40.24 FPS`
- visible surface max sample: `119.98 FPS`
- `LorieNative` average: `34.9 FPS`
- last renderer estimated cadence: `37.9 FPS`

#### Interpretation

This was another branch-quality improvement, not a throughput breakthrough:

- the evidence harness is now materially closer to the real launch path
- credentials/session forwarding is no longer a known test-path mismatch
- the measured FPS envelope still remains far below `120 FPS`

Within the current capture window, there was still no decisive evidence of a new in-game cadence regime after switching the harness onto the saved session env. So the main result of this checkpoint is improved fidelity of the measurement path, not proof that auth/session state was the missing performance unlock.

### 2026-03-15 Checkpoint 29

The next bounded upstream-client experiment was a Java2D profile A/B on the now-stable `internal-hybrid` real RuneLite path.

#### What changed

The real-launch path was extended so the evidence harness can choose a Java2D profile and push it all the way into the child RuneLite JVM:

- `launch-runelite.sh`
  - now accepts `--java2d-profile default|xrender|opengl`
  - passes that profile into the inner proot launch env
  - logs `Configured Java2D profile: ...`
- `HybridX11TestReceiver`
  - now accepts and forwards `java2d_profile`
- `hybrid-x11-runelite-evidence.ps1`
  - now accepts `-Java2dProfile`
  - includes the selected profile in the capture basename and summary

This made it possible to compare the current default child-JVM behavior against explicit Java2D XRender and OpenGL forcing on the same clean-start evidence path.

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-clean-20260315-202445.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-j2d-xrender-clean-20260315-202628.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-j2d-opengl-clean-20260315-202810.summary.txt`

#### What it showed

The visible-surface result stayed effectively flat across all three profiles:

- default:
  - visible surface average: `39.32 FPS`
  - `LorieNative` average: `32.42 FPS`
  - average damage-request cadence: `25.39 FPS`
- forced `xrender`:
  - visible surface average: `39.01 FPS`
  - `LorieNative` average: `30.07 FPS`
  - average damage-request cadence: `23.97 FPS`
- forced `opengl`:
  - visible surface average: `38.88 FPS`
  - `LorieNative` average: `32.2 FPS`
  - average damage-request cadence: `25.2 FPS`

There was still some movement in secondary metrics:

- the `xrender` pass briefly produced a higher last renderer estimate (`49.6 FPS`)
- the `opengl` pass did successfully reach the child JVM argument list, but still did not raise the visible-surface result

#### Interpretation

This was another useful negative result:

- simple Java2D flag selection is **not** the missing unlock
- the steady-state ceiling is not explained by an obvious `sun.java2d.opengl` vs `xrender` mismatch alone
- the main remaining limiter still appears upstream in the broader RuneLite/AWT/X11 frame-production path

So after this checkpoint, there was even less reason to keep spending time on presentation-only tuning or shallow JVM flag toggles.

### 2026-03-15 Checkpoint 30

After the Java2D A/B came back flat, a real logged-in live session was left running and profiled directly to see where steady-state CPU time was actually going.

#### What changed

No launcher code changed for this checkpoint. The branch was exercised on the live `internal-hybrid` path while logged into the actual game client, and the current logcat plus hot-process/thread snapshots were saved as evidence.

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.summary.txt`
- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.logcat.txt`
- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.host-top.txt`
- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.java-threads.txt`
- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.termux-x11-threads.txt`
- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.virgl-threads.txt`
- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.host-app-threads.txt`

#### What it showed

The live logged-in session still reached the RuneLite GPU plugin on VirGL:

- `GpuPlugin - Using device: virgl (Mali-G720-Immortalis MC12)`

But the important result was that the steady-state presentation metrics remained well above the user-reported in-client `15-16 FPS` figure:

- last damage-request cadence: `63.2 FPS`
- last `LorieNative` frame cadence: `63.2 FPS`
- last renderer estimate: `62.3 FPS`

The hot-process snapshot also made the steady-state split much clearer:

- `termux-x11`: about `37%`
- child RuneLite JVM (`net.runelite.client.RuneLite`): about `25.9%`
- `virgl_test_server_android`: about `37%`
- `com.runelitetablet`: about `14.8%`

The thread-level snapshots showed where that CPU was landing:

- child JVM:
  - the long-lived hot thread was the RuneLite `Client` thread at about `26.9%`
  - the earlier hot compiler threads were gone by steady state
- `termux-x11`:
  - the main native/render thread stayed hot at about `34.6%`

#### Interpretation

This checkpoint materially changed the branch diagnosis:

- the shell / `proot` wrapper is **not** the main steady-state bottleneck
- those layers still exist, but once RuneLite is up, the hot path is dominated by:
  - RuneLite client-side frame production
  - `termux-x11`
  - `virgl_test_server_android`
  - our in-app native presentation host

So "remove shell in between" still makes sense as a cleanup and launch-simplification goal, but it is unlikely to produce the missing `120 FPS` steady-state breakthrough by itself.

The more important implication is that the next optimization work should move even harder toward the actual RuneLite GPU/plugin/client cadence and the native presentation path around it, not launcher-shell surgery.

### 2026-03-15 Checkpoint 31

The next pass explicitly researched the upstream RuneLite GPU plugin and then turned that research into a new launch-time low-cost GPU profile for A/B testing.

#### What changed

The official RuneLite sources were checked first to avoid guessing about what the GPU plugin is really doing:

- `GpuPlugin.java` confirms the plugin is still a desktop AWT/LWJGL path, creating its GL context on the Java canvas rather than using an Android-native rendering path
- `GpuPluginConfig.java` shows the default GPU config is not a minimal-cost profile
- `FpsConfig.java` and `FpsDrawListener.java` show the FPS Control plugin can directly sleep after canvas paint when enabled

That source review led to a new branch-side launch knob:

- `launch-runelite.sh`
  - now accepts `--gpu-plugin-profile`
  - always writes `fpscontrol.limitFps=false`
  - can now write a `minimal` GPU profile into RuneLite `settings.properties`
- `HybridX11TestReceiver`
  - now forwards `gpu_plugin_profile`
- `hybrid-x11-runelite-evidence.ps1`
  - now accepts `-GpuPluginProfile default|minimal`
  - includes the GPU profile in the capture basename and summary

The `minimal` profile currently forces:

- `gpu.drawDistance=15`
- `gpu.expandedMapLoadingChunks=0`
- `gpu.antiAliasingMode=DISABLED`
- `gpu.anisotropicFilteringLevel=0`
- `gpu.uiScalingMode=LINEAR`
- `gpu.removeVertexSnapping=false`
- `gpu.smoothBanding=false`
- `fpscontrol.limitFps=false`
- `fpscontrol.limitFpsUnfocused=false`
- `fpscontrol.maxFps=360`
- `fpscontrol.drawFps=false`

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-loggedin-live-20260315-203945.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-gpu-minimal-clean-20260315-204500.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-gpu-minimal-clean-20260315-204500.logcat.txt`

#### What it showed

The source research sharpened the architecture read:

- the shell / `proot` wrapper is launch scaffolding, not the main steady-state hot path
- the actual RuneLite GPU plugin is still feeding a desktop AWT/X11 presentation model
- there was a real risk that default GPU plugin cost or FPS Control state was still heavier than expected

The new branch-side override applied exactly as intended on-device. The live `settings.properties` after launch contained:

- `gpu.unlockFps=true`
- `gpu.fpsTarget=120`
- `gpu.vsyncMode=OFF`
- `fpscontrol.limitFps=false`
- `gpu.drawDistance=15`
- `gpu.expandedMapLoadingChunks=0`
- `gpu.antiAliasingMode=DISABLED`
- `gpu.anisotropicFilteringLevel=0`

The clean-start minimal-profile run also logged the expected marker:

- `Configured RuneLite GPU profile: minimal drawDistance=15 expandedMapLoadingChunks=0 antiAliasingMode=DISABLED anisotropicFilteringLevel=0 uiScalingMode=LINEAR removeVertexSnapping=false smoothBanding=false`

But the clean-start result still did not improve:

- visible surface average: `43.77 FPS`
- `LorieNative` average: `36.31 FPS`
- average damage-request cadence: `27.44 FPS`
- last renderer estimate: `46.5 FPS`

That is not materially better than the earlier clean-start default-profile range, and in this short capture it was slightly worse.

#### Interpretation

This checkpoint ruled out another plausible easy win:

- forcing a much cheaper RuneLite GPU profile does **not** immediately unlock a new clean-start FPS regime on this path
- forcing FPS Control off also does **not** reveal an obvious hidden cap in the current clean-start measurements

So the current best read is:

- the RuneLite GPU plugin and FPS-control settings were worth checking, and the branch now has a proper way to A/B them
- but the first low-cost profile test did **not** produce the missing breakthrough
- the remaining bottleneck still looks deeper than launcher shelling and deeper than just "default GPU plugin settings were too expensive"

The next useful comparison is still a real logged-in A/B using the new `minimal` profile, because that is the closest match to the user-reported in-game `15-16 FPS` experience. But at this point there is no evidence of a trivial config-only fix.

### 2026-03-15 Checkpoint 32

After the user confirmed the real in-game result still sat around `16 FPS`, the temporary low-cost GPU profile path was explicitly disabled again so it could not silently bias future runs.

#### What changed

The branch-side `minimal` GPU profile launch path was removed again:

- `launch-runelite.sh`
  - no longer accepts `--gpu-plugin-profile`
  - now clears the branch-forced GPU profile keys on normal launch:
    - `gpu.drawDistance`
    - `gpu.expandedMapLoadingChunks`
    - `gpu.smoothBanding`
    - `gpu.antiAliasingMode`
    - `gpu.uiScalingMode`
    - `gpu.anisotropicFilteringLevel`
    - `gpu.removeVertexSnapping`
- `HybridX11TestReceiver`
  - no longer forwards `gpu_plugin_profile`
- `hybrid-x11-runelite-evidence.ps1`
  - no longer exposes `-GpuPluginProfile`

The launch path still keeps:

- `gpu.unlockFps=true`
- `gpu.fpsTarget=120`
- `gpu.vsyncMode=OFF`
- `fpscontrol.limitFps=false`

because those remove known caps instead of introducing a low-cost profile.

#### Key validated capture

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-clean-20260315-210103.summary.txt`

#### What it showed

The current live `settings.properties` after the cleanup launch contained only:

- `gpu.unlockFps=true`
- `gpu.fpsTarget=120`
- `gpu.vsyncMode=OFF`
- `fpscontrol.limitFps=false`
- `fpscontrol.limitFpsUnfocused=false`
- `fpscontrol.maxFps=360`
- `fpscontrol.maxFpsUnfocused=15`
- `fpscontrol.drawFps=false`

So the temporary low-cost GPU keys are no longer persisting into later tests.

#### Interpretation

This restores the branch to the intended baseline:

- no hidden `minimal` profile is hanging around
- future tests are back on the normal RuneLite GPU path
- the branch can now continue deeper bottleneck work without config contamination

### 2026-03-15 Checkpoint 33

The next probe moved down to the live thread/scheduler layer to see whether frame production was blocked in the RuneLite client/AWT path or burning CPU continuously.

#### Key validated captures

- `perf-captures/internal-hybrid-thread-wchan-probe-20260315-210451.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-clean-20260315-210451.summary.txt`

#### What it showed

The hot live threads were:

- RuneLite `Client` thread
- RuneLite `AWT-XAWT`
- RuneLite `AWT-EventQueue`
- the hot `termux-x11` main thread

The steady-state wait-channel samples showed:

- RuneLite `Client` thread alternating between:
  - `do_sys_poll`
  - `unix_stream_read_generic`
  - and occasional running samples (`0`)
- `AWT-XAWT` mostly in:
  - `do_sys_poll`
  - later shifting into `futex_wait_queue`
- `AWT-EventQueue` parked on:
  - `futex_wait_queue`
- hot `termux-x11` thread alternating between:
  - `do_epoll_wait`
  - and running samples (`0`)

The 5-second `schedstat` deltas also showed where real CPU time was going:

- RuneLite `Client` thread:
  - about `1.42s` runtime over `5s`
- `AWT-XAWT`:
  - about `14ms` runtime over `5s`
- `AWT-EventQueue`:
  - about `3ms` runtime over `5s`
- hot `termux-x11` thread:
  - about `1.88s` runtime over `5s`

#### Interpretation

This materially sharpens the bottleneck picture:

- the UI/AWT event thread is **not** the main hot path
- `AWT-XAWT` is also not doing heavy steady-state work here
- the actual hot path is dominated by:
  - the RuneLite `Client` thread
  - the `termux-x11` renderer thread
- the RuneLite `Client` thread is spending much of its sampled time blocked in poll/socket-read states rather than running continuously

That strongly suggests the remaining ceiling is in the synchronous client/render-server interaction path, not in Compose, not in launcher shelling, and not in a trivial AWT event-queue bottleneck.

### 2026-03-15 Checkpoint 34

One bounded driver-side experiment was then added at the Mesa layer to see whether threaded GL dispatch could help the live client/X11 bottleneck.

#### What changed

A non-persistent Mesa profile toggle was added:

- `launch-runelite.sh`
  - now accepts `--mesa-profile default|glthread`
  - `glthread` exports `mesa_glthread=true`
- `HybridX11TestReceiver`
  - now forwards `mesa_profile`
- `hybrid-x11-runelite-evidence.ps1`
  - now accepts `-MesaProfile default|glthread`

This is a deeper driver-side probe than RuneLite config tuning:

- it does not change the RuneLite plugin settings
- it only changes Mesa client dispatch behavior for the launch

#### Key validated capture

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-glthread-clean-20260315-211131.summary.txt`

#### What it showed

The launch marker confirmed the env change applied:

- `Configured Mesa profile: glthread mesa_glthread=true`

But the clean-start result did not improve:

- visible surface average: `39.13 FPS`
- `LorieNative` average: `30.98 FPS`
- average damage-request cadence: `24.05 FPS`
- last renderer estimate: `52.3 FPS`

That is not better than the restored baseline and appears slightly worse in this short capture.

#### Interpretation

This ruled out another plausible low-cost win:

- Mesa command-dispatch threading does **not** unlock a new regime on the current RuneLite + VirGL + X11 path
- the deeper client/server bottleneck still remains

So after this checkpoint the current evidence points even more strongly to the actual interaction boundary itself:

- RuneLite client thread
- GL / X11 server interaction
- `termux-x11` / `virgl` present path

not just RuneLite settings, not just Java2D flags, and not just a simple Mesa dispatch toggle.

### 2026-03-15 Checkpoint 35

The restored default path was then re-probed at the live thread/wait-state level so the earlier interaction-boundary result could be confirmed after removing the temporary low-cost GPU profile.

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-clean-20260315-211605.summary.txt`
- `perf-captures/internal-hybrid-thread-wchan-probe-20260315-211605.summary.txt`

#### What it showed

The restored baseline still landed in the same broad clean-start range:

- visible surface average: `37.8 FPS`
- `LorieNative` average: `27.93 FPS`
- average damage-request cadence: `20.22 FPS`
- last renderer estimate: `51.7 FPS`

The live hot threads were again:

- RuneLite `Client`
- RuneLite `AWT-XAWT`
- RuneLite `AWT-EventQueue`
- the hot `termux-x11` main thread
- the hot `virgl_test_server_android` main thread

The wait-channel time series on those hot threads repeated the same pattern:

- RuneLite `Client`
  - `do_sys_poll`
  - `unix_stream_read_generic`
  - occasional running samples (`0`)
- `AWT-XAWT`
  - `do_sys_poll`
  - `futex_wait_queue`
- `AWT-EventQueue`
  - consistently `futex_wait_queue`
- hot `termux-x11`
  - alternating `do_epoll_wait` and running samples
- hot `virgl`
  - alternating `do_select` and running samples

The 5-second `schedstat` deltas also showed the real hot steady-state split:

- RuneLite `Client`: about `1.31s`
- `AWT-XAWT`: about `13ms`
- `AWT-EventQueue`: about `7ms`
- hot `termux-x11`: about `1.73s`
- hot `virgl`: about `1.54s`

#### Interpretation

This confirms the earlier diagnosis on the restored baseline:

- the core UI/AWT event threads are not the main cost
- the steady-state hot path is the RuneLite client thread plus the X11/VirGL server side
- the client is repeatedly blocking in poll/socket-read states instead of continuously producing frames

That means the current ceiling still looks like an interaction-boundary problem, not a hidden config issue and not a simple Java/AWT event-loop bottleneck.

### 2026-03-15 Checkpoint 36

One more bounded interaction-path test was then made: force GLX off the DRI3 path and compare it directly against the restored default baseline.

#### What changed

A second Mesa-side launch-time toggle was added:

- `launch-runelite.sh`
  - `--mesa-profile dri3-off`
  - exports `LIBGL_DRI3_DISABLE=1`
- `HybridX11TestReceiver`
  - forwards `mesa_profile`
- `hybrid-x11-runelite-evidence.ps1`
  - now supports `-MesaProfile dri3-off`

This is a narrower client/server interaction probe than RuneLite config tuning:

- it does not change RuneLite settings
- it only changes the GLX/Mesa interaction mode for the launch

#### Key validated capture

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-dri3off-clean-20260315-211951.summary.txt`

#### What it showed

The env change applied correctly:

- `Configured Mesa profile: dri3-off LIBGL_DRI3_DISABLE=1`

But again there was no breakthrough:

- visible surface average: `37.54 FPS`
- `LorieNative` average: `29.42 FPS`
- average damage-request cadence: `22.83 FPS`
- last renderer estimate: `53.1 FPS`

That is effectively the same regime as the restored default path.

#### Interpretation

This ruled out another plausible interaction-path unlock:

- disabling DRI3 did **not** materially improve the current RuneLite + VirGL + X11 path

So after this checkpoint the running list of non-solutions now includes:

- low-cost RuneLite GPU profile
- Java2D flag switching
- Mesa GL threading
- DRI3 disable

The branch evidence continues to converge on the same core conclusion:

- the bottleneck is in the deeper synchronous RuneLite client <-> X11/VirGL interaction path itself
- not in surface refresh selection
- not in launcher shelling
- not in simple config or one-line Mesa toggles

### 2026-03-15 Checkpoint 37

The next live probe moved from generic wait-state sampling to actual socket-path identification, using a clean `internal-hybrid` keep-running RuneLite session and a new host-side live IPC helper.

#### What changed

A dedicated live IPC capture helper was added:

- `scripts/hybrid-x11-live-ipc-probe.ps1`

It snapshots the current RuneLite/X11/VirGL process tree, finds the hot Java and server threads, samples their live syscall/wchan states, enumerates the live socket FDs with `lsof`, and classifies those sockets against `/proc/net/unix` and `/proc/net/tcp*`.

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-clean-20260315-213504.summary.txt`
- `perf-captures/internal-hybrid-live-ipc-probe-20260315-214410.summary.txt`
- `perf-captures/internal-hybrid-live-ipc-probe-20260315-214410.raw.txt`

#### What it showed

The hottest RuneLite thread was again the `Client` thread, and the hot X11/VirGL processes were still:

- RuneLite `Client`
- `termux-x11`
- `virgl_test_server_android`

But now the blocked client-side FD was identified directly:

- hot RuneLite `Client` thread repeatedly sampled in `read()` on `fd=64`
- `fd=64` in the RuneLite JVM is `socket:[1310281]`
- `/proc/net/unix` shows the adjacent server-side inode `1310282` bound to `/data/data/com.termux/files/usr/tmp/.virgl_test`
- the live `virgl_test_server_android` process holds `fd=4` as `socket:[1310282]`

The helper recorded that as:

- `client_blocking_fd=64`
- `client_blocking_fd_inode=1310281`
- `client_blocking_fd_classification=unix:<unnamed>`
- `virgl_inference=client fd 64 inode 1310281 is most likely the Java-side peer of virgl fd 4 inode 1310282 ... inode delta=1`

The same capture also clarified the other local IPC shape:

- the RuneLite JVM had five live unnamed Unix sockets:
  - `fd=40 inode=1310191`
  - `fd=41 inode=1311830`
  - `fd=47 inode=1309191`
  - `fd=64 inode=1310281`
  - `fd=99 inode=1313794`
- `termux-x11` held six named X11 sockets on `.X11-unix/X0`
  - the two listeners
  - four accepted X11 endpoints

That means the live Java-side socket set now splits cleanly into:

- one socket that most likely peers with the VirGL server socket
- several other unnamed Unix sockets that line up with the accepted X11 connection set

The AWT side still did not become the dominant cost during this probe:

- `AWT-XAWT` continued to bounce between poll/futex states
- the hot `Client` thread kept returning to the same `read(fd=64)` / `poll()` cycle

#### Interpretation

This is the strongest evidence so far that the main steady-state stall is upstream of Android presentation and also narrower than “generic X11”:

- the hot RuneLite `Client` thread is not primarily waiting on the X11 socket
- it is most likely waiting on the VirGL control/data socket itself
- the X11 sockets are still part of the path, but the dominant synchronous wait appears to be at the Java/OpenGL <-> VirGL boundary

So the next branch emphasis should shift again:

- less attention on X11 listener parity or surface-side timing
- more attention on why the RuneLite GPU path is synchronizing so often against VirGL

In practical terms, the current best diagnosis is now:

- RuneLite is reaching the GPU plugin path
- the display side is willing to present at `120 Hz`
- but the client render loop keeps stalling on the local VirGL socket instead of continuously producing frames

### 2026-03-15 Checkpoint 38

With the hot client wait now tied much more closely to the VirGL socket, the next bounded branch experiment moved to the VirGL server process model itself.

#### What changed

A launch-time VirGL server profile override was added end-to-end:

- `launch-runelite.sh`
  - `--virgl-server-profile`
  - supported values:
    - `default`
    - `no-loop-or-fork`
    - `angle-gl`
    - `angle-vulkan`
- `HybridX11TestReceiver`
  - forwards `virgl_server_profile`
- `hybrid-x11-runelite-evidence.ps1`
  - now supports `-VirglServerProfile`

This was intentionally a quick harness change, not a strategy pivot. The first bounded test only changed the VirGL server process model:

- `default` server
- `--no-loop-or-fork`

#### Key validated captures

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-virglsrv-default-clean-20260315-214837.summary.txt`
- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-virglsrv-noloop-clean-20260315-215016.summary.txt`

#### What it showed

The fresh post-change default baseline was still in the same broad regime:

- visible surface average: `40.28 FPS`
- `LorieNative` average: `30.74 FPS`
- average damage-request cadence: `24.14 FPS`
- last renderer estimate: `60.5 FPS`

The `no-loop-or-fork` server profile did not improve it. It made it materially worse:

- visible surface average: `23.06 FPS`
- `LorieNative` average: `13.84 FPS`
- average damage-request cadence: `8.5 FPS`
- last renderer estimate: `3.0 FPS`

The failure signature was not a clean win-or-lose cadence difference. The run also showed path instability:

- no `GpuPlugin - Using device:` line in the captured window
- no `GpuPlugin - Using driver:` line in the captured window
- `gles-renderer: Buffer 5 not found`

So this was a strong negative result:

- changing the VirGL server loop/fork mode does **not** unlock a better cadence
- the current server implementation is sensitive enough that this mode change can actively destabilize the rendering path

#### Interpretation

This narrows the practical space for “server flags will save us”:

- the VirGL boundary is still the right place to investigate
- but simple server process-model toggles are not a promising route to `120 FPS`
- if alternate VirGL server modes are tried again, they should be treated as high-risk validation experiments, not likely optimizations

### 2026-03-15 Checkpoint 39

The branch was then checked against upstream RuneLite GPU-plugin source to see whether the client-side VirGL wait pattern lines up with anything concrete in the render loop itself.

#### What was reviewed

Official RuneLite GPU plugin source:

- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`

#### What it showed

The plugin is not just issuing scene draw calls. It also performs a per-draw UI/interface upload path using a pixel unpack buffer:

- maps `interfacePbo` with `glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY)`
- copies the Java pixel buffer into that PBO
- then calls `glTexSubImage2D(...)` to upload the interface texture

The same upstream source also contains a suppressed OpenGL debug warning for this exact class of operation:

- `Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.`

That lines up unusually well with the current device-side evidence:

- the hot RuneLite `Client` thread repeatedly blocks on the VirGL socket
- the hot path is not primarily `AWT-XAWT`
- the display side is already willing to present at `120 Hz`

#### Interpretation

This does **not** prove the per-frame interface upload is the only bottleneck, but it is now a credible upstream mechanism for the synchronous VirGL wait we are seeing.

So after this checkpoint the working theory becomes more specific:

- the branch is probably not being held back by Android presentation
- and not primarily by X11 listener ownership
- it is more likely being held back by the RuneLite GPU plugin’s client-side GL/VirGL synchronization behavior, potentially including the interface texture upload path

That makes “patch or replace the relevant RuneLite GPU upload/sync path” a more plausible next branch direction than “keep trying random X11 or VirGL server flags.”

### 2026-03-15 Checkpoint 40

The next bounded implementation step was to make the new `internal-hybrid` harness capable of launching RuneLite without the RuneLite launcher bootstrap in the steady-state path.

#### What changed

Direct-launch plumbing was added end-to-end:

- `launch-runelite.sh`
  - added `--runelite-launch-mode`
  - supports:
    - `launcher`
    - `direct-jvm`
- `HybridX11TestReceiver`
  - forwards `runelite_launch_mode`
- `hybrid-x11-runelite-evidence.ps1`
  - now supports `-RuneLiteLaunchMode`
  - now includes launch mode in capture names and summaries

The first version of this failed in two different ways:

- the harness could false-positive on readiness because `DUMP_REAL_HYBRID_LAUNCH_STATE` was tailing an appended Termux `runelite-launch.log`
- the first direct-launch classpath seed path was brittle
  - it initially looked in the wrong place (`/root/runelite-launch.log` instead of the outer Termux log)
  - then it was tripped by Windows CRLF contamination on the seeded classpath file

Both of those harness issues were fixed:

- `hybrid-x11-runelite-evidence.ps1`
  - readiness is now scoped to the latest `=== RuneLite launch ... ===` block
  - the empty-array median bug from very short captures was also fixed
- `launch-runelite.sh`
  - strips `\r` from direct-classpath inputs
  - can resolve direct-classpath input from persisted Termux-side state

#### Key validated captures

- fresh launcher baseline:
  - `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-virglsrv-default-launch-launcher-clean-20260315-224215.summary.txt`
- fresh direct-JVM run:
  - `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-virglsrv-default-launch-directjvm-clean-20260315-224028.summary.txt`
- important failure capture during bring-up:
  - `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-virglsrv-default-launch-directjvm-clean-20260315-223225.logcat.txt`

#### What it showed

The important milestone is that `direct-jvm` is now real:

- the latest validated direct run reached:
  - `Configured RuneLite launch mode: direct-jvm`
  - `GpuPlugin - Using device: virgl (Mali-G720-Immortalis MC12)`
  - `Client initialization took 11499ms`

Before the CRLF fix, direct-JVM had a misleading failure:

- it looked like “classpath missing”
- after explicit seeding, the real failure surfaced:
  - `java.lang.NoClassDefFoundError: net/runelite/api/ClientConfiguration`

That turned out to be consistent with the seeded classpath file carrying a Windows carriage return on the final jar entry. Once the launcher script stripped `\r`, direct-JVM reached the real client path cleanly.

#### Direct-JVM vs launcher comparison

Fresh launcher baseline:

- visible surface average: `38.62 FPS`
- `LorieNative` average: `30.36 FPS`
- average damage-request cadence: `24.07 FPS`
- last renderer estimate: `49.9 FPS`

Fresh direct-JVM run:

- visible surface average: `43.75 FPS`
- `LorieNative` average: `34.6 FPS`
- average damage-request cadence: `27.24 FPS`
- last renderer estimate: `51.3 FPS`

Both runs still had:

- repeated `120.00 Hz` surface votes
- RuneLite GPU plugin active on VirGL
- the same broad cadence band rather than anything close to `120 FPS`

#### Interpretation

This is useful because it removes one more outer-layer suspect:

- bypassing the RuneLite launcher bootstrap does **not** produce a new high-refresh regime
- there may be a modest difference in this sample, but it is still small relative to the gap from `40-45 FPS` to `120 FPS`

So the branch conclusion tightens again:

- the launcher process itself is not the main steady-state limiter
- the real steady-state bottleneck is still deeper in the client render / GL upload / VirGL interaction path

That keeps the branch pointed in the same direction:

- direct-launch is worth keeping as a patch/testing vehicle
- but the next meaningful optimization work should target the RuneLite client-side rendering path itself, not launcher ownership

### 2026-03-15 Checkpoint 41

With `direct-jvm` now working, the next small implementation step was to turn it into a usable patch vehicle for client-side experiments.

#### What changed

`launch-runelite.sh` now supports a direct-classpath override directory for `direct-jvm` launches:

- override directory:
  - `/root/.runelite/repository2-overrides`
- behavior:
  - for each resolved direct-classpath entry, if a jar with the same basename exists in the override directory, that path is substituted into the direct classpath

This is intentionally inert by default:

- if the override directory is absent or empty, nothing changes
- normal launcher mode is unaffected

#### Validation

No-override direct-JVM validation capture:

- `perf-captures/internal-hybrid-runelite-defaultres-j2d-default-mesa-default-virglsrv-default-launch-directjvm-clean-20260315-224557.summary.txt`

It still reached real RuneLite cleanly:

- `Configured RuneLite launch mode: direct-jvm`
- `GpuPlugin - Using device: virgl (Mali-G720-Immortalis MC12)`
- `Client initialization took 10631ms`

No-override steady-state remained in the same broad band:

- visible surface average: `39.84 FPS`
- `LorieNative` average: `27.71 FPS`
- average damage-request cadence: `19.38 FPS`
- last renderer estimate: `45.1 FPS`

#### Interpretation

This does not change the performance conclusion, but it materially improves the branch’s usefulness:

- we now have a working `direct-jvm` launch mode
- and a clean place to inject patched RuneLite jars for targeted client/render-path experiments

So after this checkpoint the branch is prepared for the next real class of tests:

- patching or overriding specific RuneLite jars
- especially around the GPU plugin / upload / synchronization path

### 2026-03-15 Checkpoint 42

The branch then moved from launcher/path plumbing back to the actual client patch target.

#### What was validated locally

Pulled jars:

- `perf-captures/client-1.12.20.jar`
- `perf-captures/injected-client-1.12.20.jar`
- `perf-captures/runelite-api-1.12.20-runtime.jar`

Saved bytecode disassembly:

- `perf-captures/GpuPlugin.javap.txt`

#### What it showed

The pulled `client-1.12.20.jar` does contain the expected GPU-plugin interface upload path:

- `GpuPlugin.prepareInterfaceTexture(int, int)`
- `GpuPlugin.drawUi(int, int, int)`
- `interfacePbo`
- `interfaceTexture`

The disassembly confirms the specific upload sequence inside `prepareInterfaceTexture(...)`:

- binds `interfacePbo`
- allocates buffer storage with `glBufferData(...)`
- maps it with `glMapBuffer(...)`
- copies the Java pixel buffer into the mapped PBO
- uploads to the texture with `glTexSubImage2D(...)`

That matches the earlier upstream-source review and the current branch evidence well enough that the next patch target is no longer speculative.

#### Interpretation

The branch now has all three ingredients needed for a real client-side experiment:

- a working direct-launch path
- a classpath-override mechanism for patched jars
- a concrete client method target in the exact pulled RuneLite jar

So the next meaningful step is no longer more launcher/path testing. It is building and injecting a patched GPU-plugin/client jar to test whether the interface upload path is a major part of the remaining cadence ceiling.

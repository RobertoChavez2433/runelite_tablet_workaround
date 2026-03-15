# Pragmatic Route: Presentation Layer Options

## Goal

Keep the real RuneLite client on the tablet without root, preserve the working auth/runtime work we already have, and find the shortest path to stable full-screen high refresh.

## Constraints

- No root.
- Open-source friendly distribution.
- Keep official-account auth working.
- Preserve as much of the current launch/session stack as possible.
- Full-screen is non-negotiable.
- High refresh matters enough that the presentation layer must be treated as a first-class problem, not a polish task.

## What The Current Stack Proved

The current app already proved several hard problems:

- Android app orchestration works.
- GeckoView/Jagex auth works.
- Termux/proot can run the real Linux ARM64 RuneLite build.
- Native VirGL can get RuneLite onto the GPU path.

That means the current stack was a good proof-of-possibility architecture. It was not wasted work.

What it has **not** proved is that `Termux:X11` can deliver a polished full-screen high-refresh presentation path on this tablet.

## Why The Current Bottleneck Points At Presentation

The latest local evidence says:

- GPU plugin is active.
- Full-screen presentation is much slower than smaller-window presentation.
- `Termux:X11` still owns the visible Android surface.
- Samsung/SurfaceFlinger behavior around that surface is unstable.

This lines up with Android's rendering model: refresh-rate and frame-pacing APIs are attached to the visible `Surface` / `SurfaceControl` that the app owns.

Relevant Android docs:

- Frame rate API overview: https://developer.android.com/media/optimize/performance/frame-rate
- `Surface.setFrameRate(...)`: https://developer.android.com/reference/android/view/Surface
- `SurfaceControl.Transaction.setFrameRate(...)`: https://developer.android.com/reference/android/view/SurfaceControl.Transaction
- `SurfaceView.setRequestedFrameRate(...)`: https://developer.android.com/reference/android/view/SurfaceView

Implication:

- Today, `Runelite for Tablet` does not own the critical presentation surface.
- `com.termux.x11` owns it.
- That makes our control over refresh, pacing, and surface lifecycle indirect at best.

## Option 1: Direct Android Surface, Keep Linux RuneLite

### What It Means

Keep:

- Android app
- GeckoView auth
- Termux/proot Linux runtime
- RuneLite Linux build

Replace:

- `Termux:X11` as the visible full-screen display owner

Target state:

- Our app owns the Android `SurfaceView` / `SurfaceControl`
- We ask for high refresh directly
- We decide buffer cadence directly
- Linux-side rendering feeds that surface through a custom bridge

### Why It Could Help

- It attacks the actual bottleneck we are seeing: the final Android presentation path.
- It preserves the most valuable work already completed.
- It gives us a path toward `Surface.setFrameRate`, `SurfaceControl.Transaction.setFrameRate`, and eventually better pacing control.

### What Makes It Hard

- There is no ready-made off-the-shelf "drop-in replacement for Termux:X11" for this use case.
- `Termux:GUI` is not that. It exposes native Android GUI components to CLI apps; it is not an X11/desktop-OpenGL host:
  https://github.com/termux/termux-gui
- We would need to build or adapt a custom bridge between Linux-side rendering and an Android-owned surface.

### Likely Technical Shapes

1. Custom Android host surface backed by a native bridge.
2. Custom minimal display server that presents into our app's surface.
3. Buffer handoff from a host-side renderer into our own `SurfaceControl`.

### Judgement

This is the best first experimental path.

Reason:

- It preserves the most.
- It attacks the right layer.
- It does not commit us yet to a full client rewrite.

### Success Criteria

- Our app owns the visible surface.
- We can request high refresh on that surface.
- A simple accelerated test scene reaches the surface without `Termux:X11`.
- Focus/lifecycle stays inside our app instead of bouncing across apps.

## Option 2: Thinner Graphics Bridge / Direct Mesa Path

### What It Means

Keep Linux RuneLite, but reduce or bypass parts of:

`RuneLite -> Mesa virpipe -> virgl server -> Termux:X11 -> Android compositor`

The idea is to get closer to:

`RuneLite -> Mesa / Vulkan / EGL wrapper -> Android native surface`

### Why It Could Help

- It removes translation layers and presentation overhead.
- It attacks both the rendering bridge and the presentation path together.

### What The External Work Suggests

There is active Termux-community exploration around exactly this kind of Android graphics wrapper work:

- Termux packaging discussion around `ANativeWindow`, `vulkan-wsi-layer`, `mesa-vulkan-icd-wrapper`, GLVND/EGL integration:
  https://github.com/termux/termux-packages/issues/22292
- Vulkan WSI layer for Android:
  https://github.com/xMeM/vulkan-wsi-layer
- Mesa Zink driver requirements:
  https://docs.mesa3d.org/drivers/zink.html

This is important because it shows a serious direction exists, but it is still low-level, fast-moving, and not a polished consumer path.

### Device-Specific Reality

For this tablet:

- Mali matters.
- Earlier Zink optimism from Adreno/Turnip does not transfer cleanly.
- Mesa's Zink requirements and our prior local findings both suggest real feature-risk on Mali.

### Judgement

This has the highest upside short of a native port, but it is riskier than Option 1.

Reason:

- It depends on low-level Android graphics integration work.
- It may still run into Mali-specific constraints.
- It is harder to isolate than "change who owns the surface."

### When To Use It

- If Option 1 fails because a direct-surface host still cannot feed frames efficiently enough.
- Or if the direct-surface spike quickly reveals that we must also replace the current VirGL/X11 bridge, not just the UI owner.

## Option 3: Native Android Host / Client Path

### What It Means

Stop treating Android as a launcher for a Linux desktop stack and move toward Android-native ownership of:

- surface
- frame pacing
- lifecycle
- input
- windowing

There is concrete prior art that RuneLite-style or RuneLite-API Android work is possible:

- `openosrs-mobile`:
  https://github.com/open-osrs/openosrs-mobile

And Android's game stack is designed for exactly the presentation control we currently lack:

- AGDK / GameActivity:
  https://developer.android.com/games/agdk/get-agdk
  https://developer.android.com/games/agdk/game-activity/get-started
- Frame Pacing / Swappy:
  https://developer.android.com/games/sdk/frame-pacing/opengl/add-functions
  https://developer.android.com/stories/games/swappy
- Game loop rendering guidance:
  https://developer.android.com/games/develop/gameloops

### Why It Could Help

- The app would own the surface directly.
- High refresh, pacing, and buffer lifecycle become first-class app concerns.
- UX becomes much more product-like.

### What Makes It Hard

- Highest implementation cost by far.
- Largest uncertainty around preserving full RuneLite behavior and plugin compatibility.
- Requires a host/runtime model that is much further from the current codebase.

### Judgement

This is the strongest long-term architecture if polished full-screen high-refresh is mandatory.

It is not the pragmatic first move because we already have a functioning auth/runtime stack and should first see whether the presentation bottleneck can be removed without discarding that work.

## Recommended Route

### Route Summary

1. Keep `main` focused on the currently working stack.
2. Create an experimental branch for a direct Android surface spike.
3. Treat that spike as a proof-of-feasibility exercise, not a rewrite.
4. If the spike fails for presentation-only reasons, investigate the thinner graphics bridge path next.
5. If both fail or become too low-level/device-specific, move to native-host planning.

### Why This Order

- It preserves working auth/session/runtime work.
- It attacks the actual bottleneck first.
- It limits rewrite cost until the simpler path is disproven.

## Direct-Surface Spike Scope

### What The Spike Should Do

1. Extract the current presentation path behind a small abstraction in the app.
2. Keep the existing `Termux:X11` backend as the stable implementation.
3. Add an experimental "direct surface" backend slot, even if it is only a stub at first.
4. Centralize all current X11-specific launch/switch/preference behavior.
5. Build a minimal Android-owned surface test path before trying to feed real RuneLite through it.

### What The Spike Should Not Do

- Do not rewrite auth.
- Do not throw away Termux/proot yet.
- Do not attempt a full native RuneLite port in the same spike.
- Do not mix "surface ownership" changes with "rewrite the renderer" changes unless forced by evidence.

## Milestones

### Milestone A: Architecture Extraction

- App has a `PresentationBackend` abstraction.
- Current behavior remains `Termux:X11`.
- No behavior regression on the stable path.

### Milestone B: App-Owned Surface Prototype

- Experimental screen/activity owns a `SurfaceView` or equivalent.
- Requests high refresh directly.
- Presents a known animated test scene with instrumentation.

### Milestone C: Bridge Feasibility Check

- Determine whether a Linux-side or host-side rendering bridge can target the app-owned surface with acceptable cost.

### Milestone D: Decision Gate

- If presentation path looks viable, continue deeper.
- If not, pivot to thinner graphics bridge research/implementation.
- If that still looks weak, plan the native-host route.

## Carry-Forward Inventory

### Likely To Survive Regardless

- GeckoView/Jagex auth
- credential/session handling
- setup/install flow
- logging
- much of the launcher/session control logic

### Most Likely To Change

- `Termux:X11` app handoff logic
- X11 preference broadcasts
- `Switch to Game` flow
- some of `launch-runelite.sh`'s display ownership assumptions

## Bottom Line

The current stack was the correct architecture for proving RuneLite can run at all on a non-rooted tablet with real auth.

It is probably not the final architecture if the product bar is:

- full-screen
- stable
- consumer-friendly
- high refresh

The pragmatic next move is not a full rewrite. It is a direct-surface spike that preserves the working runtime stack and tests whether we can take ownership of the Android presentation layer.

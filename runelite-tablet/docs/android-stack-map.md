# Android Stack Map

This is the actual launch and rendering stack for RuneLite on the tablet.

It looks complicated because RuneLite is a Linux desktop Java app, not a native Android app. Android cannot run it directly, so we have to build a bridge across several layers.

## Why The Layers Exist

1. Android app layer
   - `RuneLite for Tablet` provides setup, permissions, launch UX, shutdown UX, and log visibility.
   - It is the controller, not the renderer.

2. Termux layer
   - Android cannot directly run arbitrary Linux shell launch flows the way a normal desktop does.
   - Termux gives us a userland environment, package manager, shell, and the `RUN_COMMAND` entry point.

3. `Termux:X11`
   - RuneLite expects an X11 desktop display, not an Android `SurfaceView`.
   - `Termux:X11` is the X server that exposes a desktop-style display to Linux apps and owns the actual Android window that ends up on screen.

4. `proot-distro` Ubuntu
   - RuneLite and its Java/X11 dependencies are expected in a desktop-like Linux environment.
   - `proot` gives us a rootfs without requiring root access on the tablet.

5. VirGL
   - RuneLite’s GPU path speaks desktop OpenGL.
   - Android GPUs expose GLES/Vulkan, not desktop GL in the way RuneLite expects.
   - VirGL bridges desktop GL calls from Mesa inside `proot` to a host-side renderer on Android.

6. Android compositor
   - Even after all of that, the final pixels still have to go through `Termux:X11`’s Android `SurfaceView`, then SurfaceFlinger, then Samsung display policy.

## High-Level Flow

```text
RuneLite for Tablet app
  -> Termux RUN_COMMAND
  -> launch-runelite.sh
  -> fresh Termux:X11
  -> VirGL server
  -> proot Ubuntu
  -> openbox
  -> RuneLite JVM
  -> Mesa virpipe
  -> virgl socket
  -> host GLES
  -> Termux:X11 SurfaceView
  -> Android SurfaceFlinger
  -> tablet display
```

## Launch Path

### 1. App-side control

`SetupViewModel.launch()` in [SetupViewModel.kt](/C:/Users/rseba/Projects/Tablite/runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt)

```text
User taps Launch
  -> app sends com.termux.x11.CHANGE_PREFERENCE broadcast
  -> app launches Termux:X11 once
  -> app starts launch-runelite.sh through com.termux.RUN_COMMAND
  -> app waits for display.ready
  -> app does one readiness-based switch back into Termux:X11
```

Why this exists:
- Android wants an Activity-driven foreground handoff.
- The actual shell bootstrap is better run in the background.

### 2. Shell bootstrap

`launch-runelite.sh` in [launch-runelite.sh](/C:/Users/rseba/Projects/Tablite/runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh)

```text
launch-runelite.sh
  -> clean previous session
  -> create session dir + PID files
  -> start PulseAudio
  -> start fresh Termux:X11
  -> wait for X11 socket
  -> start native virgl_test_server_android
  -> create session sentinel
  -> enter proot Ubuntu
```

Why this exists:
- We need deterministic fresh startup.
- Android app code should not be directly responsible for Linux process orchestration.

### 3. Linux desktop bootstrap

Inside `proot`:

```text
proot Ubuntu
  -> export DISPLAY=:0
  -> export VirGL/Mesa env
  -> start openbox
  -> gather xrandr/glxinfo diagnostics
  -> write display.ready
  -> launch RuneLite.jar
```

Why this exists:
- RuneLite wants a normal desktop session with X11, a window manager, and desktop GL semantics.

## Process Tree

When a session is healthy, it looks like this logically:

```text
com.runelitetablet (Android app)
  -> RuneLiteSessionService

com.termux (Android app / process host)
  -> bash launch-runelite.sh
     -> termux-x11 / com.termux.x11.Loader
     -> virgl_test_server_android
     -> proot-distro login ubuntu
        -> openbox
        -> java -jar RuneLite.jar
```

Important note:
- `Termux:X11` is both an Android app and part of the Linux display stack.
- That dual role is exactly why Android policy can hurt performance even when RuneLite itself is rendering correctly.

## Data Paths

### Control path

```text
RuneLite for Tablet
  -> Android intents / service calls
  -> Termux RUN_COMMAND
  -> shell scripts
```

This path is responsible for:
- launch
- permissions
- shutdown
- health checks

If this path is broken, symptoms look like:
- wrong screen
- launch never starts
- duplicate sessions
- shutdown leaves children behind

### Graphics path

```text
RuneLite
  -> OpenGL calls
  -> Mesa virpipe in proot
  -> /tmp/.virgl_test socket
  -> virgl_test_server_android
  -> host GLES
  -> Termux:X11 SurfaceView
  -> SurfaceFlinger
```

This path is responsible for:
- GPU rendering
- frame pacing
- actual FPS

If this path is broken, symptoms look like:
- GPU plugin active but poor FPS
- black frames
- bad depth/FBO behavior
- `NO_BUFFER_AVAILABLE`
- refresh collapsing to 30 Hz

### Input path

```text
touch input
  -> Android
  -> Termux:X11 Activity / SurfaceView
  -> X11
  -> RuneLite
```

This path is responsible for:
- taps
- camera movement
- game interaction

## Shared Files And Sockets

These are the important bridge points:

1. X11 socket
   - `$PREFIX/tmp/.X11-unix/X0`
   - Connects Linux X11 clients to `Termux:X11`

2. VirGL socket
   - `$PREFIX/tmp/.virgl_test`
   - Connects Mesa `virpipe` in `proot` to the Android-side VirGL server

3. Session root
   - `$PREFIX/tmp/rlt-session/<session-id>/`
   - Stores state, PID files, and `display.ready`

4. Session sentinel
   - `$PREFIX/tmp/.rlt-session-alive`
   - Basic “session still up” marker used by health monitoring

## What We Have Proven So Far

1. Launch/control path is mostly working.
   - The app can launch the stack correctly.
   - Duplicate launches are blocked.

2. Native VirGL is the right backend on this tablet.
   - Earlier ANGLE-based VirGL was broken.

3. The GPU path is alive.
   - RuneLite can use `virgl`.
   - Standalone native VirGL probe works.

4. The remaining bottleneck is the presentation path.
   - Fresh standalone probe reached `2960x1848 @ 119.99`.
   - But standalone `glxgears` still only delivered roughly low-20s FPS.
   - Logcat shows `BLASTBufferQueue ... NO_BUFFER_AVAILABLE`.
   - Samsung still says `com.termux.x11` is not a game app.

That means:
- the stack is no longer failing only because of RuneLite
- the Android/X11 surface path is a real throughput bottleneck by itself

## How To Debug By Symptom

### Symptom: Launch goes to wrong screen or hangs

Check:
- app logs
- `runelite-launch.log`
- session state files
- duplicate launch lock

Relevant files:
- [SetupViewModel.kt](/C:/Users/rseba/Projects/Tablite/runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt)
- [launch-runelite.sh](/C:/Users/rseba/Projects/Tablite/runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh)

### Symptom: GPU plugin is off or GL is broken

Check:
- RuneLite log
- VirGL server log
- GL harness results

Relevant files:
- [launch-runelite.sh](/C:/Users/rseba/Projects/Tablite/runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh)
- [frame-pacing.sh](/C:/Users/rseba/Projects/Tablite/runelite-tablet/gl-tests/scripts/frame-pacing.sh)
- [run-tests.sh](/C:/Users/rseba/Projects/Tablite/runelite-tablet/gl-tests/scripts/run-tests.sh)

### Symptom: FPS is bad even when GPU is active

Check:
- `Termux:X11` visibility and lifecycle
- SurfaceFlinger refresh selection
- `BLASTBufferQueue` errors
- Samsung game classification / freezing

This is the current hot path.

## Why Android Thinks This Is Not A Game

From Android/Samsung’s point of view:
- the visible app is `com.termux.x11`
- not `net.runelite.client.RuneLite`

So even though the pixels are a game, the package metadata belongs to `Termux:X11`.
That is why Samsung’s game heuristics can miss it.

## Current Best Mental Model

Think of the system as two halves:

```text
Control half
  app -> intents -> Termux -> shell -> session control

Rendering half
  RuneLite -> Mesa/VirGL -> Termux:X11 SurfaceView -> Android compositor
```

The control half is now mostly working.

The rendering half is where the remaining performance problem lives.

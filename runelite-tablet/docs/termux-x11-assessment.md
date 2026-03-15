# Termux:X11 Assessment For Direct Android Surface

## Executive summary

`Termux` is still the right runtime/orchestration layer for now.

`Termux:X11` is not the right final presentation layer if the goal is a seamless fullscreen, high-refresh RuneLite experience. The recent direct-surface probe already proved that an app-owned `SurfaceView` on this tablet can sustain `120 fps`, while the `Termux:X11` fullscreen path collapses badly under the same device conditions.

After inspecting the upstream `termux-x11` code, the important conclusion is:

- the renderer and surface path are technically portable into our app
- the separate `Termux:X11` APK is mostly a packaging/launch choice, not a hard rendering requirement
- the best next experiment is to fork a minimal `Xlorie` host into our app, not to keep fighting the external `Termux:X11` activity

## What upstream `termux-x11` actually is

There are two separate pieces:

1. `shell-loader`
- Tiny loader APK started from Termux with:
  - `app_process`
  - `CLASSPATH=/.../loader.apk`
  - main class `com.termux.x11.Loader`
- It verifies the installed target APK signature and then reflects into `com.termux.x11.CmdEntryPoint`.

2. `app`
- A normal Android app with:
  - `MainActivity`
  - `LorieView` (`SurfaceView`)
  - native X server + renderer in `libXlorie.so`

Important upstream files:

- `termux-x11/termux-x11`
- `shell-loader/src/main/java/com/termux/x11/Loader.java`
- `app/src/main/java/com/termux/x11/CmdEntryPoint.java`
- `app/src/main/java/com/termux/x11/MainActivity.java`
- `app/src/main/java/com/termux/x11/LorieView.java`
- `app/src/main/cpp/lorie/renderer.c`
- `app/src/main/cpp/lorie/activity.c`

## Why the separate APK exists

The upstream split is mostly about launch security and packaging:

- `shell-loader` is hardwired to `APPLICATION_ID = "com.termux.x11"`
- it verifies the APK signature before loading code
- it allows the Termux-side command to stay tiny and stable

That is important, but it also means the stock loader is not directly reusable as-is for our package. If we adopt this design, we would need our own forked loader or our own equivalent `app_process` entrypoint.

## What is portable into our app

### 1. The visible surface path

`LorieView` is already a plain Android `SurfaceView`.

When the surface changes, it passes the Java `Surface` into native code:

- `LorieView.surfaceChanged(...)`
- native `activity.c::surfaceChanged(...)`
- `renderer.c::rendererSetWindow(...)`

The renderer then creates an `ANativeWindow` and an EGL window surface:

- `ANativeWindow_fromSurface(...)`
- `eglCreateWindowSurface(...)`
- `eglMakeCurrent(...)`

That is exactly the kind of path we want. It means the Android-side presentation logic is not fundamentally tied to being in a separate APK.

### 2. The X server entrypoint

`CmdEntryPoint` is the Java/native bridge that starts the X server process and exposes binder methods back to the activity for:

- X connection socket fd
- logcat fd

This can also be forked into our package.

### 3. The activity-connection handshake

`MainActivity` receives the binder, calls `getXConnection()`, and hands the fd into `LorieView.connect(...)`.

That handshake is app-local code, not Android magic. We can replace `MainActivity` with our own minimal host activity.

## What is not a good fit to keep wholesale

### 1. The external `Termux:X11` activity ownership

This is the current bottleneck.

Android only sees `com.termux.x11` as the visible surface owner, which means:

- Samsung classifies the wrong app
- our app does not own frame pacing
- our app does not own the visible surface lifecycle
- we get handoff, focus, and battery-management problems

### 2. Upstream UI/prefs/notification behavior

The stock app carries a lot of desktop-X11 UI behavior that we do not want for a consumer game launcher:

- preferences activity
- extra keys toolbar
- notification-driven controls
- generic touchpad/mouse-mode flows
- DeX/secondary display behaviors

We should not embed all of that.

### 3. Loader hardcoding

The stock loader is compiled against:

- `com.termux.x11`
- `com.termux.x11.CmdEntryPoint`

So if we want the same model, we need a forked loader that targets our package/class.

### 4. GPLv3 implication

`termux-x11` is GPLv3. Forking its app/native code into our app is feasible, but it is a licensing decision, not just a technical one.

Because the product is intended to be open source, this may be acceptable. But it should be treated as an explicit project-level choice.

## Best pragmatic path

### Recommended path: fork a minimal Xlorie host into our app

Keep:

- `Termux`
- `proot`
- RuneLite Linux runtime
- current auth/orchestration/session logic

Replace:

- external `Termux:X11` presentation ownership

Build:

1. A minimal forked X11 host module inside our app
- fork only:
  - `CmdEntryPoint`
  - AIDL interface
  - `LorieView`
  - native `activity.c` / `renderer.c` / related required Xlorie pieces
- strip:
  - prefs UI
  - notifications
  - extra keys UI
  - unrelated desktop UX paths

2. A new in-app activity
- owns the fullscreen `SurfaceView`
- stays in our package
- becomes the actual destination for `Switch to Game`

3. A forked loader/launcher path
- Termux still needs a way to start the X server via `app_process`
- simplest route is a small fork of the upstream shell-loader with:
  - our package id
  - our entrypoint class

### Why this is better than keeping stock `Termux:X11`

- our app owns the visible surface
- our app can request frame rate on the real game surface
- no cross-app handoff churn
- no separate-app background launch edge cases
- no Samsung misclassification of the wrong foreground app

## Not recommended

### 1. Keep stock `Termux:X11` and try to automate around it

We already have enough evidence that this is the wrong side of the boundary to keep optimizing forever.

### 2. Reuse the stock shell-loader unchanged

It is compiled specifically for `com.termux.x11`. This is not the right foundation for an app-owned surface.

### 3. Full native Android RuneLite port right now

That is still a valid long-term option, but it is not the next pragmatic move while Linux RuneLite is already running successfully.

## Concrete next implementation steps

1. Vendor upstream `termux-x11` source into a `third_party` area or documented external ref.
2. Create a minimal `x11host` package/module inside `runelite-tablet`.
3. Fork the shell-loader concept so Termux can start our package's `CmdEntryPoint`.
4. Replace the direct-surface probe activity with a real `X11HostActivity`.
5. Point the launcher flow at the in-app host instead of `com.termux.x11`.
6. Re-test full-screen pacing before touching RuneLite-specific rendering again.

## Decision

`Termux` remains the right tool for the runtime layer.

`Termux:X11` is useful as an upstream donor/reference implementation, but not as the intended final fullscreen presentation tool for this project.

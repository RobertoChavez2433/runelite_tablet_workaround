# Approaches for Running Java Desktop Apps on Android

## Status: Researched 2026-02-21

---

## Approach 1: Native Android Port (PojavLauncher Model)

**How PojavLauncher runs Minecraft Java on Android:**
- Bundles custom OpenJDK mobile ports (JDK 8, 17, 21) for ARM32/ARM64/x86/x86_64
- Custom LWJGL3 fork bridges desktop graphics to Android
- GL4ES translates desktop OpenGL to OpenGL ES (mobile-compatible)
- Custom GLFW stub provides window abstraction on Android
- Native code rewrites input pipe: touch/hardware → mouse/keyboard events
- LWJGLX provides LWJGL2 API compatibility for older code
- Mesa 3D for software rendering fallback
- bhook for process lifecycle management

**Applicability to RuneLite:**
- RuneLite uses LWJGL for GPU plugin (OpenGL 4.3+ required)
- RuneLite uses Java Swing/AWT for UI
- Would need: custom AWT implementation (like meteor-mobile does), LWJGL bridge, OpenGL ES translation
- PojavLauncher proves the JVM + OpenGL bridge approach works on Android

## Approach 2: Termux + proot-distro + Termux:X11

**How it works:**
- Termux provides Linux terminal environment on Android (no root required)
- proot-distro runs full Linux distros (Ubuntu/Debian) in a chroot-like environment
- Termux:X11 provides X server for graphical apps
- Install OpenJDK inside the Linux distro
- Run RuneLite's Linux ARM64 AppImage or JAR

**Requirements:**
- Android 8.0+
- 6GB+ RAM (Samsung Tab S10 Ultra has 12-16GB — plenty)
- Snapdragon 845+ recommended (Tab S10 Ultra has Snapdragon 8 Gen 3)
- 10GB+ storage

**Performance considerations:**
- proot adds overhead (user-space syscall translation)
- X11 rendering adds latency
- No GPU acceleration by default (software rendering)
- Some community guides mention GPU acceleration with virgl/virpipe
- Proven to run Minecraft via MultiMC in this setup

**Pros:** No root required, uses official RuneLite ARM64 build, full plugin support
**Cons:** Performance overhead, complex setup, no GPU plugin

## Approach 3: Winlator (Windows Emulation)

**How it works:**
- Wine + Box86/Box64 on Android
- Runs Windows x86/x64 applications on ARM Android
- Supports DirectX 9/10/11, OpenGL, Vulkan translation

**Applicability:**
- Could potentially run the Windows RuneLite + Jagex Launcher directly
- Would solve the auth problem (run Jagex Launcher natively in emulation)
- Snapdragon 8 Gen 3 support status unclear (was noted as "not yet supported")
- Java/JVM overhead on top of emulation overhead could be significant

**Pros:** Could run entire Jagex Launcher + RuneLite as-is
**Cons:** Heavy emulation overhead, uncertain Snapdragon 8 Gen 3 support, unclear Java performance

## Approach 4: Remote Desktop / Streaming

**Options:**
- Parsec, Moonlight, Steam Link, Chrome Remote Desktop
- Run RuneLite on a PC, stream to tablet

**Pros:** Full RuneLite experience, no porting needed, all plugins work
**Cons:** Requires always-on PC, latency (especially for gaming), internet dependency
**Verdict:** Not a real "port" — user explicitly wants RuneLite ON the tablet

## Approach 5: Samsung DeX Mode

**What it provides:**
- Desktop-like interface on Samsung tablets
- Full mouse + keyboard support
- Resizable app windows
- NOT a Linux environment — still Android

**Relevance:** DeX doesn't solve the "run desktop Java" problem, but it provides the input infrastructure (mouse/keyboard) that any solution would need. DeX is the INPUT layer, not the execution layer.

---

## Key Technical Challenges

### 1. AWT/Swing on Android
- Android doesn't have java.awt or javax.swing
- Solutions: Custom AWT implementation (meteor-mobile), headless AWT, or don't use Swing (render directly)

### 2. OpenGL 4.3 on Android
- Android provides OpenGL ES (not desktop OpenGL)
- RuneLite GPU plugin needs OpenGL 4.3+
- Solutions: GL4ES translation layer, software rendering fallback, or port GPU plugin to OpenGL ES

### 3. Java Applet (game client)
- OSRS client historically based on java.applet.Applet
- No Applet support on Android
- Solution: Replace Applet container with custom Android component (as meteor-mobile does)

### 4. Jagex Launcher Authentication
- Desktop-only OAuth flow
- Solutions: hotlite-client approach (classpath injection), run launcher in emulation, or legacy account support only

### 5. Input Mapping
- Touch → mouse click translation
- On-screen keyboard or physical keyboard support
- Right-click emulation (long press or keyboard modifier)
- Samsung Tab S10 Ultra supports physical keyboard + mouse via DeX — this is actually well-suited

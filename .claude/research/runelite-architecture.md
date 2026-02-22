# RuneLite Technical Architecture

## Status: Researched 2026-02-21

---

## Technology Stack

- **Language**: Java (primarily)
- **Build System**: Maven (main repo), Gradle (some forks)
- **UI Framework**: Java Swing/AWT (sidebar panels, settings, overlays)
- **GPU Rendering**: LWJGL (Lightweight Java Game Library) with OpenGL 4.3+
- **Source**: https://github.com/runelite/runelite

## Core Architecture

### Game Client
- OSRS client is based on `java.applet.Applet` (legacy)
- RuneLite deobfuscates and injects into the official client
- Reflection-based client injection for hooking game methods
- The game client itself is downloaded and patched at runtime

### Rendering System

**Software Renderer (default):**
- CPU-based rendering inherited from the original OSRS client
- Capped at 50 FPS
- Uses Java AWT for drawing

**GPU Plugin (optional, recommended):**
- Replaces CPU renderer with hardware-accelerated OpenGL
- Uses LWJGL for OpenGL context and bindings
- Requires OpenGL 4.3+ (desktop OpenGL, NOT OpenGL ES)
- Implements `DrawCallbacks` interface to intercept game rendering
- Multi-stage shader pipeline: Vertex → Geometry → Fragment
- Compute shaders for face sorting (OpenGL or OpenCL on macOS)
- Texture management: 2D texture arrays, 128x128, max 256 textures
- Features: extended draw distance, anti-aliasing, fog, anisotropic filtering, FPS unlock
- `SceneUploader` converts game scene data to GPU-compatible format

### Plugin System
- Plugins loaded via dependency injection (Google Guice)
- Plugin Hub: community plugins reviewed and distributed
- Plugins can: add overlays, modify UI, intercept game events, add hotkeys
- Plugin API provides access to game state, rendering callbacks, network events
- Plugins are JAR files loaded at runtime

### Key Dependencies That Affect Android Portability
1. **java.awt / javax.swing** — UI panels, overlays, settings → NOT available on Android
2. **LWJGL** — OpenGL bindings → Needs custom build for Android (PojavLauncher proves this is possible)
3. **java.applet.Applet** — Game client container → NOT available on Android
4. **OpenGL 4.3** — GPU plugin → Android only has OpenGL ES (need GL4ES or rewrite)
5. **JVM reflection** — Client injection → Should work on Android JVM (Dalvik/ART) with adjustments
6. **Desktop JVM (HotSpot)** — Runs on standard Java SE → Android uses Dalvik/ART (different bytecode)

## Module Structure (main repo)

```
runelite/
├── runelite-api/         # Plugin API interfaces
├── runelite-client/      # Main client application
│   ├── plugins/          # Built-in plugins
│   │   ├── gpu/          # GPU rendering plugin (LWJGL/OpenGL)
│   │   └── ...           # Many other plugins
│   ├── ui/               # Swing-based UI
│   └── ...
├── runelite-jshell/      # JShell integration
├── runescape-api/        # RS game API interfaces
├── runescape-client/     # Deobfuscated client
├── cache/                # Game cache management
├── http-api/             # RuneLite web API
└── http-service/         # Backend service
```

## Build & Distribution
- Maven-based build
- Distributed as JAR (launched by Jagex Launcher or standalone)
- Auto-updates via RuneLite's update mechanism
- Available for: Windows, macOS, Linux (x64 + ARM64)
- Linux ARM64 AppImage available (relevant for Android/ARM)

## Key Insights for Android Port

1. **Software rendering works without GPU** — meteor-mobile proves AWT software renderer runs at 50fps on Android
2. **Plugin system is framework-agnostic** — Guice DI works on any JVM, plugins don't need Swing
3. **GPU plugin needs significant rework** — OpenGL 4.3 → OpenGL ES translation or GL4ES layer
4. **The RuneLite "client" is actually two things**: the RuneLite wrapper (plugins, UI, overlays) and the actual OSRS game client (the Applet). Both need Android solutions.
5. **ARM64 Linux build exists** — RuneLite already compiles for ARM64, which is the Android CPU architecture

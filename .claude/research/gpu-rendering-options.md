# GPU Rendering Options for RuneLite on Android

## Status: Researched 2026-02-21

---

## RuneLite GPU Plugin Requirements
- OpenGL 4.3+ for full functionality (compute shaders for face sorting, extended draw distance)
- OpenGL 4.0 minimum if compute shaders disabled (loses extended draw distance — macOS does this)
- GLSL 430 for compute shader programs
- LWJGL for OpenGL context and bindings

---

## Option 1: Mesa Zink (MOST PROMISING)

**What**: Mesa Gallium driver that translates desktop OpenGL → Vulkan
**OpenGL version**: 4.6 (maximum) — Khronos-conformant as of Mesa 24.1
**Compute shaders**: YES — full ARB_compute_shader support

### How it works on Android

**A) Zink Direct via zink-xlib-termux:**
- https://github.com/alexvorxx/zink-xlib-termux
- Renders directly via Xlib in Termux, no virgl intermediary
- Reports OpenGL 4.6+ available
- Tested on Adreno 630 with proprietary Vulkan driver
- Fewest translation layers = best performance

**B) Zink + VirGL (proot-distro):**
- virgl_test_server runs on Android with Zink backend
- Guest apps in proot connect via virpipe driver
- OpenGL 4.3 available
- Extra translation layer adds overhead but still usable

**C) Zink compiled into native Android app (NDK):**
- Build Mesa with `-Dgallium-drivers=zink` using Android NDK + Meson
- Results in `libGL.so` providing full desktop OpenGL 4.6
- Most direct path, least overhead
- Requires significant integration work

### Performance
- ~80% of native OpenGL driver performance on desktop Linux
- For OSRS's relatively simple geometry, this should be more than sufficient
- Zink + Turnip (open-source Adreno Vulkan driver) reported as best for Qualcomm

### Snapdragon 8 Gen 3 / Adreno 750 Compatibility
- Vulkan 1.3 supported (proprietary + Turnip)
- Vulkan 1.3 includes all features Zink needs for OpenGL 4.6
- Some Adreno 7xx issues reported with mesa-zink but development is active

---

## Option 2: GL4ES — NOT VIABLE

- Translates OpenGL 2.1 → OpenGL ES 2.0
- Maximum OpenGL version: **2.1** — RuneLite needs 4.0+
- No compute shader support
- Used by PojavLauncher for Minecraft but insufficient for RuneLite GPU plugin

---

## Option 3: VirGL Alone — NOT VIABLE

- Can expose OpenGL 4.3 theoretically
- PojavLauncher's VirGL renderer gets **1-2 FPS** — unusable
- Extra serialization overhead kills performance

---

## Option 4: ANGLE — NOT VIABLE

- Implements OpenGL ES, NOT desktop OpenGL
- Wrong direction — we need desktop OpenGL, not ES
- OpenGL ES 3.1 has compute shaders but different API from desktop

---

## Option 5: No GPU Plugin (Software Rendering) — FALLBACK

- Default OSRS software renderer: 50fps cap
- No extended draw distance, no anti-aliasing, no HD
- But works out of the box with no GPU translation needed
- meteor-mobile uses this approach successfully

---

## PojavLauncher Case Study (Reference)

| Renderer | OpenGL | Compute Shaders | Performance |
|----------|--------|-----------------|-------------|
| Holy GL4ES | 2.1 | NO | Best FPS |
| Zink (Turnip) | 4.5 | YES | Good |
| VirGL | 4.3 | Yes (theory) | 1-2 FPS |

**Key lesson**: Zink with Turnip on Adreno achieves OpenGL 4.5 with good performance.

---

## Recommended Path for RuneLite

### Primary: Zink + Turnip in Termux/proot

Environment variables:
```bash
# Host (Termux):
MESA_NO_ERROR=1 MESA_GL_VERSION_OVERRIDE=4.3COMPAT MESA_GLES_VERSION_OVERRIDE=3.2 \
GALLIUM_DRIVER=zink ZINK_DESCRIPTORS=lazy \
virgl_test_server --use-egl-surfaceless --use-gles

# Guest (proot):
DISPLAY=:0 MESA_NO_ERROR=1 MESA_GL_VERSION_OVERRIDE=4.3COMPAT GALLIUM_DRIVER=virpipe
```

### Fallback: Software rendering (no GPU plugin)
- Disable GPU plugin in RuneLite settings
- 50fps software rendering
- All other plugins still work

### Future: Zink NDK build
- Compile Mesa Zink for Android NDK
- Bundle as native library in the installer app
- Direct OpenGL 4.6 without virgl overhead
- Best long-term performance solution

---

## Key Resources
- [zink-xlib-termux](https://github.com/alexvorxx/zink-xlib-termux)
- [Mesa Android build docs](https://docs.mesa3d.org/android.html)
- [Termux-Desktops HW Acceleration](https://github.com/LinuxDroidMaster/Termux-Desktops/blob/main/Documentation/HardwareAcceleration.md)
- [gpu_accel_termux](https://github.com/ThatMG393/gpu_accel_termux)
- [PojavLauncher Renderers](https://pojavlauncher.app/wiki/faq/android/RENDERERS.html)
- [RuneLite GPU Plugin source](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java)

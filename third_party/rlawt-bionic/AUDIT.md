# rlawt Vendor Audit

**Upstream**: https://github.com/runelite/rlawt
**Commit**: `ecb6599caaaa12b1ddfe4d955cceb2e69fb06702`
**Tag**: `v1.8`
**Date vendored**: 2026-04-17

## Tag Match (U2)

Checked out `git tag v1.8`, which matches the version shipped in RuneLite's `rlawt-1.8.jar`. Commit is `ecb6599` — the HEAD at time of vendoring. Tag list at clone time ran `v1.0` through `v1.8`.

## Build System (U1)

Upstream uses **CMake 3.15+**.

- `CMakeLists.txt` — single top-level file, ~44 LoC.
- `cmake/toolchain-linux-aarch64.cmake` — sample glibc cross-compile toolchain (aarch64-linux-gnu-gcc). **We do not use this.** Our build substitutes the NDK toolchain.
- `.github/workflows/build.yml` — CI builds Windows x86/amd64/aarch64, macOS x86_64/aarch64, Linux amd64/aarch64 (glibc). Linux aarch64 is the target we replace.

### Key CMake behavior

- `find_package(Java 1.8 REQUIRED)` + `find_package(JNI REQUIRED)` — auto-detect host JDK. Used for `add_jar()` to compile `AWTContext.java` and generate the JNI header via `javac -h` (emits `net_runelite_rlawt_AWTContext.h`).
- `add_library(rlawt SHARED rlawt.c rlawt_nix.c rlawt_windows.c)` — all three source files included unconditionally; `#ifdef _WIN32` / `#ifdef __unix__` / `#ifdef __APPLE__` gates inside each file restrict code generation to the right platform.
- `target_link_libraries(rlawt rlawt-headers ${JNI_LIBRARIES})` — baseline links JNI.
- On `UNIX`: `target_link_libraries(rlawt GL GLX)` — links `-lGL -lGLX`. Under NDK sysroot + harvested Termux stubs, this resolves to Termux's `libGL.so` and `libGLX.so` (both Bionic-sonamed).

Our adaptation will:
1. Supply `CMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake`
2. Override `JAVA_AWT_LIBRARY` / `JAVA_JVM_LIBRARY` / `JAVA_INCLUDE_PATH` / `JAVA_AWT_INCLUDE_PATH` to point at Termux openjdk-21 harvest paths
3. Use host JDK 17 to generate the JNI header via `add_jar` (ABI stable vs target JDK 21)
4. Keep `target_link_libraries(rlawt GL GLX)` — NDK will resolve against the harvested sysroot stubs

## Source File Inventory

| File | LoC | Platform Gate | Notes |
|---|---|---|---|
| `rlawt.c` | 204 | cross-platform (JNI shared) | includes `<X11/Xlib.h>` + `<GL/glx.h>` inside `#ifdef __unix__` |
| `rlawt_nix.c` | 296 | `#ifdef __unix__` | GLX context creation; X11 error handler; swap buffers |
| `rlawt_windows.c` | ~200 | `#ifdef _WIN32` | inactive on Android |
| `rlawt_mac.m` | N/A | `#ifdef __APPLE__` | inactive on Android; not compiled by our CMakeLists invocation |
| `rlawt.h` | 125 | cross-platform | struct `AWTContext`; includes jawt.h + jawt_md.h |
| `AWTContext.java` | 181 | target | Java-side. `loadNatives()` selects `linux-aarch64/librlawt.so` when `os.contains("nux") && arch=="aarch64"` |

Android (Bionic) behavior: `__unix__` IS defined by NDK clang → `rlawt_nix.c` active, `rlawt.c` unix branch active. `__APPLE__` / `_WIN32` / `__ANDROID__` gates: NDK clang defines `__ANDROID__=1` but no `__ANDROID__`-specific code paths exist in rlawt — treated uniformly as `__unix__`.

## glibc Scan (A3)

Grep for glibc-specific headers and feature macros returned zero hits:

```
grep -rE '#include\s*<(malloc|features|epoll|sys/sysinfo)\.h>|__GLIBC__|__GNU_LIBRARY__|gnu_\w+|_GNU_SOURCE' third_party/rlawt/
# no matches
```

Include set (full scan of `.c` + `.h`):

- `rlawt.h` (project), `net_runelite_rlawt_AWTContext.h` (CMake-generated)
- `<jawt.h>`, `<jawt_md.h>` (JDK)
- `<stdbool.h>`, `<stdlib.h>`, `<string.h>` (libc stable ABI)
- `<X11/Xlib.h>`, `<GL/glx.h>` (inside `__unix__` gate)
- `<windows.h>`, `<wingdi.h>`, `<GL/gl.h>`, `<wglext.h>` (inside `_WIN32` gate; not compiled for Android)
- `<OpenGL/OpenGL.h>`, `<IOSurface/IOSurface.h>`, `<QuartzCore/CALayer.h>` (inside `__APPLE__` gate; not compiled for Android)

Verdict: **zero glibc-specific assumptions**. All unix-side code is standard POSIX + X11 + GLX + JAWT. Bionic libc provides all 5 libc functions rlawt calls (`snprintf`, `memset`, `calloc`, `free`, `strstr`).

## Symbol Map (A4)

Expected undefined-symbol providers after NDK build:

| Symbol | Provider .so | Path on device |
|---|---|---|
| `snprintf`, `memset`, `calloc`, `free`, `strstr` | Bionic libc | NDK sysroot `libc.so` → device `/system/lib64/libc.so` |
| `__stack_chk_fail`, `__stack_chk_guard` | Bionic libc (stack protector) | same |
| `__cxa_finalize`, `__gmon_start__`, `_ITM_*` | weak (optional); resolved or ignored | — |
| `glXQueryExtension`, `glXChooseFBConfig`, `glXGetFBConfigAttrib`, `glXQueryExtensionsString`, `glXGetProcAddressARB`, `glXCreateContextAttribsARB`, `glXCreateNewContext`, `glXDestroyContext`, `glXMakeCurrent`, `glXSwapBuffers`, `glXGetProcAddress`, `glXSwapIntervalEXT` (fn ptr), `glXSwapIntervalSGI` (fn ptr), `glFinish` | Termux Mesa `libGL.so` | `/data/data/com.termux/files/usr/lib/libGL.so` |
| `XOpenDisplay`, `XCloseDisplay`, `XDisplayString`, `XSync`, `XSetErrorHandler`, `XFree` | Termux `libX11.so` | `/data/data/com.termux/files/usr/lib/libX11.so` |
| `JAWT_GetAWT` | Termux `libjawt.so` | `/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib/libjawt.so` |

Total expected: **~26 undefined non-weak + 4-6 weak**. Well under the 30-symbol S77 baseline.

## Headers Provenance (U3)

| Header | Source | Harvest method |
|---|---|---|
| `jni.h` | Termux `openjdk-21` JDK include dir on device | `adb pull` via `run-as com.termux` (Phase 21B) |
| `jawt.h` | same | same |
| `jni_md.h` | Termux `openjdk-21` include/linux | same |
| `jawt_md.h` | same | same |
| `X11/*.h` (Xlib.h + deps) | Termux `libx11` package (installed on device) include dir | `adb pull /data/data/com.termux/files/usr/include/X11/` |
| `GL/gl.h`, `GL/glx.h`, `GL/glext.h`, `GL/glxext.h` | **NOT on device** (Termux `mesa` package ships only runtime .so, not headers) → vendor from Khronos/Mesa upstream | Download into `third_party/rlawt/include/GL/` as part of Phase 21B |

Note: `net_runelite_rlawt_AWTContext.h` is generated at build time by `add_jar(... GENERATE_NATIVE_HEADERS rlawt-headers)`. Requires host `javac`. Available on this machine at `/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot/bin/javac`.

## CMake Link-Line Summary (A5)

Original CMakeLists.txt Linux link line resolves to:

```
aarch64-linux-gnu-gcc -shared rlawt.o rlawt_nix.o rlawt_windows.o \
    ${JNI_LIBRARIES} \
    -lGL -lGLX
```

where `${JNI_LIBRARIES}` from `find_package(JNI)` is the target JDK's `libjawt.so` + `libjvm.so` paths.

Our NDK adaptation link line will resolve (via cmake vars) to:

```
<NDK>/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android31-clang -shared rlawt.o rlawt_nix.o rlawt_windows.o \
    <harvest>/libjawt.so <harvest>/libjvm.so \
    <harvest>/libGL.so <harvest>/libGLX.so
```

Emitted NEEDED sonames will be the sonames embedded in each linked `.so` — the harvested Termux stubs have Bionic sonames (`libjawt.so`, `libjvm.so`, `libGL.so`, `libGLX.so`) with no `.6` glibc suffixes and no VERNEED sections. NDK toolchain contributes `libc.so` and `libdl.so` NEEDED entries (Bionic sonames) via `-Wl,--needed` defaults.

## Red Flags / Risks

1. **rlawt.c line 161 — `glXGetProcAddressARB`**. `"glXCreateContextAttribsARB"` resolved at runtime via `glXGetProcAddressARB`. Requires Mesa's GLX extension support. Termux's Mesa 26.0.5 should support this.
2. **rlawt_nix.c line 84 — `JAWT_X11DrawingSurfaceInfo`**. Requires `jawt_md.h` to define the `JAWT_X11DrawingSurfaceInfo` struct. Termux openjdk-21-x header confirmed to have this (ABI-compatible; JDK has shipped this since Java 1.4).
3. **No per-thread X11 display reuse** (line 94: `ctx->dpy = XOpenDisplay(displayName)`). Opens a fresh connection. Fine for Bionic.

## Red Flags / Not Blockers

- macOS `.m` (Objective-C) source: not compiled for Android target. Don't care about it.
- Windows sources: compiled but produce no object code on Android target (empty preprocessor gate). Can strip from our build target if noise.

## Conclusion

Source is Bionic-safe. Rebuild is purely a toolchain change. Proceeding to Phase 21B (NDK wiring + header harvest).

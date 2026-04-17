# rlawt — Bionic Rebuild for Termux

This directory contains the Bionic-compatible rebuild of rlawt that lets
RuneLite's JVM run directly under Termux without proot. See
`../../.claude/plans/2026-04-17-rlawt-bionic-rebuild.md` for the full
multi-phase plan and `../../.claude/specs/2026-04-17-rlawt-bionic-rebuild-spec.md`
for the verification-gate spec.

Upstream source (untouched) is under `../rlawt/`. This dir only contains
the NDK wiring, harvested target headers, and build recipe — not source.

## Layout

| Path | Purpose |
|---|---|
| `CMakeLists.txt` | Minimal Android NDK build spec. References `../rlawt/` sources; does not modify them. |
| `harvest/` | Target headers + stub .so files pulled from the Samsung Tab S10 Ultra's Termux install. Not a tarball — checked in for offline reproducibility. |
| `harvest/jdk-include/` | openjdk-21 JNI + JAWT headers (from Termux openjdk-21 package) |
| `harvest/x11-include/X11/` | X11 protocol headers (Termux `libx11` provides Xlib.h; xorgproto-2024.1 upstream provides X.h/keysym.h/etc. not shipped by Termux) |
| `harvest/gl-include/GL/` | Mesa 26.0.5 GL + GLX headers (upstream; Termux doesn't ship GL dev headers) |
| `harvest/sysroot-stubs/` | Bionic-sonamed .so stubs from Termux: libjawt, libGL, libGLX, libX11. Used as link targets so NDK linker embeds correct Bionic sonames into NEEDED. (libjvm.so is not harvested — rlawt resolves JNI symbols through the host JVM at load time, never at ELF link time.) |
| `generated/` | Pre-generated JNI header (`net_runelite_rlawt_AWTContext.h`) built with host `javac -h`. Avoids needing host Java at build time. |
| `test/` | MiniAwtContext.java — end-to-end smoke test that proves the jar classpath + native load path work under Termux-native openjdk-21. |
| `audit-symbols.sh` | On-device auditor for undefined-symbol resolution. Pushed via adb to Termux home; uses `/system/bin/readelf` (Android built-in). |
| `build-bionic/` | Build output. Gitignored — rebuild with `scripts/build-rlawt-bionic.sh`. |
| `AUDIT.md` | Source audit. Proves no glibc-specific assumptions in upstream rlawt. |
| `UPSTREAM_COMMIT.txt` | Pinned upstream commit + tag (v1.8). |

## Build

```bash
bash scripts/build-rlawt-bionic.sh --clean
```

Requires:
- Android NDK 28.2.13676358 (default path in script; override via `ANDROID_NDK_ROOT`)
- Android SDK CMake 3.22.1+ (default path in script; override via `ANDROID_SDK_CMAKE`)

Output: `third_party/rlawt-bionic/build-bionic/librlawt.so` — drop into
`net/runelite/rlawt/linux-aarch64/librlawt.so` of a repackaged
`rlawt-1.8.jar`.

## Verification

1. `readelf -d librlawt.so` — NEEDED entries must be Bionic sonames (no `.6`, no `ld-linux-aarch64.so.1`). VERNEED should reference only libc.so with Bionic `LIBC` version (not glibc `GLIBC_X.Y`).
2. `nm -u librlawt.so` — ≤ 30 undefined symbols.
3. On device (Termux shell): run `audit-symbols.sh` — every symbol must be FOUND.
4. `MiniRlawtLoad.java` — load the .so under Termux openjdk-21-x. Must print `SUCCESS: librlawt.so loaded`.
5. `MiniAwtContext.java` — load the repackaged jar and call `AWTContext.loadNatives()`. Must print `AWTContext natives loaded via rlawt-1.8-bionic.jar`.

See `runelite-tablet/docs/logs/rlawt-bionic-load-success.log` and
`rlawt-bionic-awt-smoke.log` for captured evidence.

## Re-harvesting

If Termux packages change or we target a different device, re-harvest with:

```bash
# JDK headers
adb shell 'run-as com.termux sh -c "
  mkdir -p /data/data/com.termux/files/home/harvest-staging/linux
  cp /data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/include/*.h /data/data/com.termux/files/home/harvest-staging/
  cp /data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/include/linux/*.h /data/data/com.termux/files/home/harvest-staging/linux/
  cp /data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/lib/libjawt.so /data/data/com.termux/files/home/harvest-staging/
  cp /data/data/com.termux/files/usr/lib/libGL.so /data/data/com.termux/files/home/harvest-staging/
  cp /data/data/com.termux/files/usr/lib/libGLX.so /data/data/com.termux/files/home/harvest-staging/
  cp /data/data/com.termux/files/usr/lib/libX11.so /data/data/com.termux/files/home/harvest-staging/
  chmod -R a+r /data/data/com.termux/files/home/harvest-staging
"'
# Then pull each file via: adb exec-out "run-as com.termux cat <path>" > <local>
# X11 headers via: adb exec-out "run-as com.termux sh -c 'cd /data/data/com.termux/files/home/harvest-staging && tar cf - x11'" | tar -C harvest/x11-include/ -xf - --strip-components=1
```

Upstream GL headers (keep version in sync with Termux Mesa):

```bash
cd third_party/rlawt-bionic/harvest/gl-include/GL
for f in gl.h glx.h glext.h glxext.h; do
    curl -fsSL "https://gitlab.freedesktop.org/mesa/mesa/-/raw/mesa-26.0.5/include/GL/$f" -o "$f"
done
```

Upstream xorgproto headers:

```bash
cd third_party/rlawt-bionic/harvest/x11-include/X11
for f in X.h Xfuncproto.h Xosdefs.h Xmd.h Xproto.h Xprotostr.h Xatom.h keysym.h keysymdef.h; do
    curl -fsSL "https://gitlab.freedesktop.org/xorg/proto/xorgproto/-/raw/xorgproto-2024.1/include/X11/$f" -o "$f"
done
```

# Shell Defects

Max 5 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [SHELL] 2026-03-10: VirGL FBO rendering completely non-functional
**Pattern**: `glClear(0,0,0,1)` + `glReadPixels` on a VirGL FBO returns A=0 (not A=255). The FBO color attachment is never written to — `glClear`, `glDrawArrays`, and `glReadPixels` all silently fail to operate on the bound FBO. All FBO-based rendering tests produce BLACK with uninitialized zeros.
**Prevention**: Test rendering to DEFAULT framebuffer first (bypass FBO) to verify VirGL renders anything. If FBOs are fundamentally broken on VirGL, redesign tests to use default framebuffer + `glReadPixels`.
**Ref**: @runelite-tablet/gl-tests/src/gl_test_harness.c (modules 4-9 all use FBOs)

### [SHELL] 2026-03-10: GL_MAX_VIEWPORT_DIMS stack buffer overflow in glGetIntegerv
**Pattern**: `glGetIntegerv(GL_MAX_VIEWPORT_DIMS, &val)` writes 2 integers but code provided a single `GLint` — stack overflow corrupts adjacent variables, causes SIGSEGV. Other multi-value queries (e.g. `GL_MAX_VIEWPORT_DIMS`) also need array buffers.
**Prevention**: Always use `GLint val[2]` for `glGetIntegerv` in query loops. Check GL docs for each enum's return count. Special-case multi-value queries in logging.
**Ref**: @runelite-tablet/gl-tests/src/gl_test_log.h (log_gl_caps)

### [SHELL] 2026-03-09: MESA_GLSL_VERSION_OVERRIDE missing for VirGL GPU plugin
**Pattern**: `MESA_GL_VERSION_OVERRIDE=4.1COMPAT` overrides the GL version string but NOT the GLSL version. RuneLite GPU plugin requires GLSL 3.30 but VirGL stock Mesa reports GLSL 1.50 max. Plugin crashes with `GLSL 3.30 is not supported`.
**Prevention**: Always set both `MESA_GL_VERSION_OVERRIDE` and `MESA_GLSL_VERSION_OVERRIDE` together. Community VirGL setups all use both (e.g., `MESA_GL_VERSION_OVERRIDE=4.3COMPAT MESA_GLSL_VERSION_OVERRIDE=430`).
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (virpipe env block)

### [SHELL] 2026-03-09: lfdevs Mesa breaks virpipe on Mali (32-bit visual BadMatch)
**Pattern**: lfdevs Mesa (mesa-for-android-container) is built for Adreno/Turnip. When installed on Mali, virpipe selects 32-bit RGBA visual but Termux:X11 root window is 24-bit RGB. `XGetSubImage()` fails with BadMatch. glxinfo crashes before printing GL strings.
**Prevention**: Use stock Ubuntu Mesa for Mali/VirGL (all community setups do this). Set `MESA_GLX_ALPHA_BITS=0` to force 24-bit visual. Use glxgears (XPutImage) not glxinfo (XGetImage) for virpipe detection.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-gpu-mali.sh

### [SHELL] 2026-03-10: LD_LIBRARY_PATH=$PREFIX/lib crashes virgl_test_server_android
**Pattern**: Self-bootstrap `export LD_LIBRARY_PATH=$PREFIX/lib` in shell scripts causes `virgl_test_server_android` to find Termux's OpenSSL 3.x (which removed `OpenSSL_add_all_algorithms`) instead of system's OpenSSL. System's `libsqlite.so` needs that symbol → ANGLE dlopen fails → SIGSEGV. Also native GLES path: `libunwindstack.so` needs `Xzs_Construct` → same pattern.
**Prevention**: Start VirGL server with `env -u LD_LIBRARY_PATH virgl_test_server_android`. Termux binaries have correct rpath baked in — never need LD_LIBRARY_PATH. Only set it for proot/non-Termux binaries.
**Ref**: @runelite-tablet/gl-tests/scripts/run-tests.sh, @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (needs same fix)

# Shell Defects

Max 5 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [SHELL] 2026-03-12: sed CR stripping in nested shell quotes strips ALL 'r' characters
**Pattern**: `sed -i s/\\r// file` in multi-layer shell quoting (Git Bash → adb → run-as → bash) reduces `\\r` to just `r`, stripping ALL 'r' characters from files. `export` becomes `expot`, `dirname` becomes `diname`.
**Prevention**: Use `tr -d "\015"` for CR stripping (octal, no escaping issues). Or push a self-contained script to `/data/local/tmp/` and run it, avoiding nested quoting entirely.
**Ref**: @runelite-tablet/gl-tests/scripts/device-run.sh

### [SHELL] 2026-03-12: Git Bash leaks $HOME/$PATH to adb shell via nested quoting
**Pattern**: In `adb shell "run-as com.termux bash -c 'export PATH=$PREFIX/bin:$PATH'"`, Git Bash expands `$PATH` to the Windows PATH before passing to adb, even through single quotes inside double quotes. Results in 2000+ char PATH with spaces, parentheses → syntax errors.
**Prevention**: Use self-contained scripts pushed to `/data/local/tmp/` and run via `adb shell "run-as com.termux bash /data/local/tmp/script.sh"`. Scripts self-bootstrap Termux env internally. Never use `$PATH` or `$HOME` in inline adb commands.
**Ref**: @runelite-tablet/gl-tests/scripts/device-run.sh

### [SHELL] 2026-03-11: GL_DEPTH_CLAMP breaks virglrenderer FBO pipeline on GLES hosts
**Pattern**: Mesa 4.5COMPAT auto-enables `GL_DEPTH_CLAMP`. GLES 3.2 host (ANGLE) has no `GL_DEPTH_CLAMP` → `GL_INVALID_ENUM`. The stale GLES error causes virglrenderer to silently discard ALL subsequent draws/clears, making FBO appear broken (A=0 after glClear). Root cause is the error cascade, not FBO itself.
**Prevention**: Set `MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp` to prevent Mesa from generating depth-clamp commands. Also match GL+GLSL versions (4.3COMPAT+430, not 4.5COMPAT+330). Use `MESA_DEBUG=1` to trace `GL_INVALID_ENUM` in virgl pipelines.
**Ref**: Termux issue #15832, @runelite-tablet/gl-tests/scripts/run-tests.sh

### [SHELL] 2026-03-11: GLES-unsupported glGetIntegerv tokens crash virpipe
**Pattern**: `GL_MAX_VARYING_FLOATS` (desktop GL 2.0, 0x8B4B) is not in GLES 3.x. Querying it via virpipe causes hard crash (not just GL_INVALID_ENUM) because virpipe translation layer dereferences null mapping. Also: `glXGetProcAddressARB` NEVER returns NULL on Mesa — stubs crash when called.
**Prevention**: Remove GLES-unsupported tokens from query tables (GL_MAX_VARYING_FLOATS, GL_MAX_CLIP_DISTANCES, GL_DEPTH_BITS, GL_STENCIL_BITS, GL_SAMPLE_BUFFERS, GL_SAMPLES, GL_SUBPIXEL_BITS). Version-gate `glGetStringi` (verify GL >= 3.0 + test index 0 before loop). Add fflush before each query for crash bisection.
**Ref**: @runelite-tablet/gl-tests/src/gl_test_log.h (log_gl_caps, int_queries[])

### [SHELL] 2026-03-09: MESA_GLSL_VERSION_OVERRIDE missing for VirGL GPU plugin
**Pattern**: `MESA_GL_VERSION_OVERRIDE=4.1COMPAT` overrides the GL version string but NOT the GLSL version. RuneLite GPU plugin requires GLSL 3.30 but VirGL stock Mesa reports GLSL 1.50 max. Plugin crashes with `GLSL 3.30 is not supported`.
**Prevention**: Always set both `MESA_GL_VERSION_OVERRIDE` and `MESA_GLSL_VERSION_OVERRIDE` together. Community VirGL setups all use both (e.g., `MESA_GL_VERSION_OVERRIDE=4.3COMPAT MESA_GLSL_VERSION_OVERRIDE=430`).
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh (virpipe env block)


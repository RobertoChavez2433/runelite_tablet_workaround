# Adversarial Review: VirGL Fix Pipeline

**Plan**: `.claude/plans/2026-03-11-virgl-fix-pipeline.md`
**Reviewer**: Adversarial Review Agent
**Date**: 2026-03-11
**Verdict**: CONDITIONAL PASS (7 MUST-FIX, 9 SHOULD-CONSIDER, 5 NITPICK)

---

## Executive Summary

The plan is well-structured with clear phase dependencies and quality gates. The root cause analysis is solid and the phased approach (fix crash first, then env, then code, then fallback) is correct. However, there are several issues that will cause implementation failures if not addressed: missing renderbuffer function pointer resolution, a dangerous `depthTex` semantic overload, incomplete `set -euo pipefail` compliance, and an unvalidated GL version downgrade that may break RuneLite's GPU plugin. The plan also has race conditions in deployment and a missing rollback strategy.

---

## MUST-FIX (7 findings)

### MF-1: Renderbuffer function pointers not resolved — will crash at runtime

**Phase**: 2, Step 2.3
**Issue**: The plan says to use `glGenRenderbuffers`, `glBindRenderbuffer`, `glRenderbufferStorage`, `glFramebufferRenderbuffer`, and `glDeleteRenderbuffers` in the modified `create_fbo()`. These are OpenGL extension functions (not core in the COMPAT context GLFW requests). The plan mentions "Add [...] to the function pointer section (if not already declared)" but the current `gl_test_harness.c` has NO renderbuffer function pointer types, NO static pointer variables, and NO resolution calls in `resolve_all_functions()`. The plan's code snippet at Step 2.3 calls bare `glGenRenderbuffers(1, &depthRbo)` etc. — these are NOT direct-linked symbols on the virpipe Mesa path. They will either be NULL function calls (SIGSEGV) or unresolved symbols (link error).

**Consequence**: Compile error or runtime SIGSEGV on every FBO creation attempt.

**Fix**: The plan must explicitly specify:
1. Five new typedefs (`PFNGLGENRENDERBUFFERSPROC`, `PFNGLBINDRENDERBUFFERPROC`, `PFNGLRENDERBUFFERSTORAGEPROC`, `PFNGLFRAMEBUFFERRENDERBUFFERPROC`, `PFNGLDELETERENDERBUFFERSPROC`)
2. Five new static pointer variables (`pglGenRenderbuffers`, etc.)
3. Five new resolution lines in `resolve_all_functions()`
4. NULL-check guard in `create_fbo()` before using them
5. Updated code in Step 2.3 to use the `pgl` prefixed pointers, not bare GL calls

### MF-2: `*depthTex = depthRbo` creates a type semantic bomb

**Phase**: 2, Step 2.3
**Issue**: The plan says `*depthTex = depthRbo; /* Reuse the output param -- caller must not use as texture */`. This overwrites a `GLuint*` that callers expect to be a texture ID with a renderbuffer ID. The `destroy_fbo()` function at line 328 calls `glDeleteTextures(1, &depthTex)` on this value. Passing a renderbuffer ID to `glDeleteTextures` is undefined behavior per the GL spec — it may silently corrupt the renderbuffer namespace or do nothing, but it will NOT free the renderbuffer.

**Consequence**: Renderbuffer resource leak on every FBO destruction. On repeated module runs (e.g., Module 9 creates multiple FBOs), this accumulates leaked GPU memory. On virglrenderer, leaked resources can exhaust the host-side resource table.

**Fix**: Change `destroy_fbo()` signature to accept a flag or change it to track whether depth is a texture or renderbuffer. The cleanest fix:
```c
static void destroy_fbo(GLuint fbo, GLuint colorTex, GLuint depthRes, int depth_is_rbo) {
    if (pglDeleteFramebuffers) pglDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &colorTex);
    if (depth_is_rbo && pglDeleteRenderbuffers)
        pglDeleteRenderbuffers(1, &depthRes);
    else
        glDeleteTextures(1, &depthRes);
}
```
Update all call sites to pass `depth_is_rbo = 1` after Phase 2.

### MF-3: 4.3COMPAT may break RuneLite GPU plugin — no validation

**Phase**: 1, Step 1.1 and Phase 5, Step 5.1
**Issue**: The plan downgrades `MESA_GL_VERSION_OVERRIDE` from `4.5COMPAT` to `4.3COMPAT`. RuneLite's GPU plugin research (`.claude/research/gpu-rendering-options.md` line 8) states: "OpenGL 4.3+ for full functionality (compute shaders for face sorting, extended draw distance)". The defects archive confirms `MESA_GL_VERSION_OVERRIDE` does NOT make function pointers available — it only changes the version string. So 4.3COMPAT vs 4.5COMPAT is equally "fake" for function pointer availability.

However, RuneLite's LWJGL binding layer checks the GL version string to gate certain code paths. Downgrading from 4.5 to 4.3 could change which code paths RuneLite selects. The plan applies this change to `run-tests.sh` (Phase 1) AND to `launch-runelite.sh` (Phase 5) but never validates that RuneLite's GPU plugin still initializes correctly with 4.3COMPAT.

**Consequence**: Phase 5 may discover that RuneLite GPU plugin requires `GL_VERSION >= 4.5` in its version string check, and the downgrade breaks it. This would require reverting the launch-runelite.sh change and finding a different FBO fix strategy.

**Fix**: Add an explicit validation step in Phase 5 (before Step 5.2) that checks RuneLite's GPU plugin initialization log for version-gated failures. Also add a note that `run-tests.sh` and `launch-runelite.sh` may ultimately need DIFFERENT `MESA_GL_VERSION_OVERRIDE` values — the test harness should use whatever makes FBO work, while RuneLite may need 4.5COMPAT for its own version checks.

### MF-4: `fflush(g_log_file)` when `g_log_file` is NULL is undefined behavior

**Phase**: 0, Step 0.2
**Issue**: The plan adds `fflush(g_log_file)` with the comment "g_log_file may be NULL -- LOG_INFO handles NULL guard". But `fflush(NULL)` flushes ALL open streams (per POSIX), which is expensive but not a crash. However, the plan's comment implies `g_log_file` could be NULL, and `fflush(NULL_pointer)` is actually well-defined in POSIX (flush all streams). The real issue is that the `LOG_INFO` macro already does `fflush(g_log_file)` in its body (line 32 of `gl_test_log.h`), so the plan's added fflush is redundant with every LOG_INFO call.

Actually, re-reading the code more carefully: the plan adds `fflush(g_log_file)` OUTSIDE of the LOG_INFO macro, directly in the loop. Since `g_log_file` could be NULL if `log_init` failed to open the file, calling `fflush(NULL)` would flush all streams (POSIX behavior). This is actually fine but wasteful. However, on some non-POSIX-compliant C libraries (Bionic/Android NDK), `fflush(NULL)` behavior may differ.

**Consequence**: On non-POSIX libc, possible crash. On POSIX libc, performance hit from flushing all streams on every iteration of a ~25-entry loop.

**Fix**: Guard the fflush:
```c
fflush(stdout);
if (g_log_file) fflush(g_log_file);
```

### MF-5: `run-tests.sh` missing `set -e` — shell script rule violation

**Phase**: 1, 2 (all shell script changes)
**Issue**: The `run-tests.sh` script at line 18 uses `set -uo pipefail` WITHOUT `-e`. The shell script rules (`.claude/rules/shell-scripts.md` Hard Rule #1) require "Always `set -euo pipefail` at the top of every script". The script has a comment "No -e: proot exit codes are unreliable" which is a valid exception (documented in the proot-specific rules), but the plan makes no mention of this deliberate exception. Any review agent checking rule compliance will flag this.

More importantly, the plan's Phase 5 modifies `launch-runelite.sh` which IS expected to have `set -euo pipefail`. The plan should verify that the launch script's error handling is compatible with the env var changes.

**Consequence**: Review agent rejection for rule violation. More critically, without `-e`, errors in the new env var setup (e.g., a typo in `MESA_EXTENSION_OVERRIDE`) will be silently ignored and the test will run with the wrong configuration, producing misleading results.

**Fix**: Add a comment in the plan acknowledging the `set -e` exception for `run-tests.sh` (proot exit codes) and note that error checking for env var setup relies on the proot command failing to parse, not shell `-e`. For `launch-runelite.sh`, verify that the script already has appropriate error handling around the env var block.

### MF-6: `MESA_EXTENSION_OVERRIDE` may not propagate through virglrenderer

**Phase**: 1, Step 1.1
**Issue**: `MESA_EXTENSION_OVERRIDE` is a Mesa client-side environment variable that modifies the extension string reported by Mesa's GL implementation. It works by adding/removing entries from the extension list that `glGetString(GL_EXTENSIONS)` returns. However, in the virpipe architecture, the GL state machine runs on the HOST (virglrenderer/ANGLE), not the GUEST (Mesa virpipe). When Mesa guest-side removes `GL_ARB_depth_clamp` from its reported extension list, this only affects what the guest application sees. It does NOT prevent virglrenderer from enabling `GL_DEPTH_CLAMP` on the host side during context creation.

The actual depth clamp enable/disable happens when virglrenderer translates Mesa's GL state commands to the host GLES context. If virglrenderer unconditionally enables `GL_DEPTH_CLAMP` based on its own capability detection (not the guest's extension list), then `MESA_EXTENSION_OVERRIDE` will have no effect on the actual behavior.

**Consequence**: If the root cause is virglrenderer enabling depth clamp on the host, `MESA_EXTENSION_OVERRIDE` will not fix it. The FBO will remain broken and the plan will fall through to Phase 2/4, but Phase 1's quality gate G1 may produce a false "still broken" result that doesn't tell you whether the extension override reached the right layer.

**Fix**: Add a diagnostic step to Phase 1: after setting `MESA_EXTENSION_OVERRIDE`, run `--module 1` and check the `gl-caps.json` extension list to verify that `GL_ARB_depth_clamp` is actually absent from the reported extensions. If it's still present, `MESA_EXTENSION_OVERRIDE` is not taking effect and the plan should skip directly to Phase 2. Also note in the plan that `MESA_EXTENSION_OVERRIDE` only affects Mesa guest-side — if virglrenderer is the problem, this won't help.

### MF-7: `diag1` variable used after scope in Module 7 diagnostic

**Phase**: 3, Step 3.3
**Issue**: The plan's code for the Module 7 diagnostic branch declares `char diag1[64]` inside an inner block (after the "Diagnostic 2" comment), then uses `diag1` in the `log_module_result()` call at the end of the function, which is outside that block's scope. In C, this is undefined behavior — the stack memory for `diag1` may be overwritten by the Diagnostic 3 block.

Looking at the plan's code:
```c
/* Diagnostic 2: Does color-only FBO (no depth) complete? */
GLuint fbo1, colorTex1;
// ...
char diag1[64];
snprintf(diag1, sizeof(diag1), "color-only FBO status=0x%04x", s1);
// ... Diagnostic 3 block ...
pglDeleteFramebuffers(1, &fbo1);
// ...
log_module_result(7, "diag", "FBO Blit Diagnostic",
    "FAIL", get_time_ms() - t0, diag1);  // <-- diag1 may be stale
```

Actually, looking more carefully, the diagnostics 2 and 3 are at the same scope level (inside the `if (g_fbo_works != 1)` block), so `diag1` should still be in scope at the `log_module_result` call. However, if Diagnostic 3's snprintf or other operations modify the stack near `diag1`, corruption is possible. This depends on compiler optimization.

**Consequence**: Garbled diagnostic message in the result log, making it harder to debug FBO issues.

**Fix**: Move `char diag1[64]` to the top of the function (before the `if` block) to ensure it's at function scope and won't be affected by stack frame changes from inner blocks. Or better, use a function-scoped `char detail[128]` and build the final message into it.

---

## SHOULD-CONSIDER (9 findings)

### SC-1: FBO probe may crash the virgl server

**Phase**: 2, Step 2.1
**Issue**: The `probe_fbo_capability()` function creates an FBO, clears it, reads pixels, and destroys it. If the root cause of the FBO bug is that certain GL operations cause virglrenderer to crash (not just produce wrong results), the probe could kill the virgl server process. The watchdog in `run-tests.sh` would detect this, but all subsequent modules would fail with "VirGL server died" rather than running with the fallback path.

**Consequence**: If virgl server crashes during probe, the entire test run produces no useful data beyond the crash point.

**Fix**: Consider wrapping the probe in a `fork()`/child process so a crash in the probe kills only the child. Or, at minimum, add a virgl server liveness check after the probe: `if (!kill(virgl_pid, 0))` — though this requires passing the PID into the harness, which may not be practical. A simpler fix: document that if the probe crashes, the fix is to disable the probe and hardcode `g_fbo_works = 0`.

### SC-2: `GLFW_VISIBLE=TRUE` may cause X11 window focus issues

**Phase**: 3, Step 3.1
**Issue**: Changing `GLFW_VISIBLE` from `FALSE` to `TRUE` means a 256x256 window will appear on the Termux:X11 display. If a window manager is running (openbox is started by `launch-runelite.sh`), the window may steal focus, trigger window management events, or interfere with any running RuneLite session. The plan says `run-tests.sh` should be run when no RuneLite session is active, but this isn't enforced.

**Consequence**: Window focus issues during test runs. If RuneLite is running, the test window could steal focus and disrupt the user's game session.

**Fix**: The preflight check in `run-tests.sh` already checks for virgl server conflicts. Add a window title hint (`glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE)` if available, or set the window title to something identifiable) and consider using `GLFW_DECORATED=FALSE` + minimal size to minimize visual disruption.

### SC-3: No rollback strategy documented

**Phase**: All phases
**Issue**: The plan has no documented rollback procedure. If Phase 1 makes FBO worse, or Phase 2 introduces a regression, or Phase 3's `GLFW_VISIBLE=TRUE` breaks something, there's no "undo" procedure. The only option is to manually revert each change, which requires remembering which files were changed and what the original values were.

**Consequence**: If a fix makes things worse, debugging becomes harder because the developer must mentally track what was changed across multiple files.

**Fix**: Add a rollback section to each phase. At minimum, note the git commit hash before each phase begins and provide `git checkout -- <files>` commands for each phase's changes. Better: create a git branch before starting implementation so the entire plan can be reverted with `git checkout <branch>`.

### SC-4: `run-tests.sh` and `launch-runelite.sh` env vars will diverge

**Phase**: 1 and 5
**Issue**: The plan modifies `run-tests.sh` in Phase 1 (add `MESA_EXTENSION_OVERRIDE`, change to 4.3COMPAT+430) and then modifies `launch-runelite.sh` in Phase 5 "with the winning configuration". But the two scripts have fundamentally different env var setups:

- `run-tests.sh` sets env vars via `PROOT_CMD="$PROOT_CMD VAR=VALUE"` (passed to proot-distro login's env command)
- `launch-runelite.sh` sets env vars via `export VAR=VALUE` inside the proot session

The plan acknowledges this difference implicitly (Phase 5 shows `export` syntax) but doesn't account for the fact that `MESA_EXTENSION_OVERRIDE`'s comma syntax (`-GL_ARB_depth_clamp,-GL_EXT_depth_clamp`) may be interpreted differently when passed on the command line (where commas could be shell metacharacters in some contexts) vs. when exported inside the proot session.

**Consequence**: The "winning config" that works in `run-tests.sh` might not work identically in `launch-runelite.sh` due to shell quoting differences.

**Fix**: In Phase 5, explicitly verify that the env vars inside the proot session match what was tested. Add a diagnostic `env | grep MESA` step inside the RuneLite launch flow to confirm values propagated correctly.

### SC-5: `GL_MAX_VARYING_FLOATS` is present in the actual code but plan says "remove"

**Phase**: 0, Step 0.1
**Issue**: The plan correctly identifies `GL_MAX_VARYING_FLOATS` (line 282), `GL_DEPTH_BITS` (line 294), `GL_STENCIL_BITS` (line 295), `GL_SAMPLE_BUFFERS` (line 296), `GL_SAMPLES` (line 297), and `GL_SUBPIXEL_BITS` (line 298) for removal. However, the actual code also has `GL_MAX_SAMPLES` (line 288) and `GL_MAX_CLIP_DISTANCES` (line 287) which are NOT in the removal list. `GL_MAX_CLIP_DISTANCES` was added in GL 3.0 and should be safe, but on GLES-backed virpipe, it could be undefined (0x3000 is actually `GL_CLIP_DISTANCE0`, not `GL_MAX_CLIP_DISTANCES` which is 0x0D32). If the header defines `GL_MAX_CLIP_DISTANCES` incorrectly, this could also crash.

**Consequence**: If `GL_MAX_CLIP_DISTANCES` is not supported by the GLES backend, querying it could produce `GL_INVALID_ENUM` (benign since the code already clears errors after each query) or in pathological cases, a crash in virpipe's command encoding.

**Fix**: Verify that all remaining queries in the `int_queries[]` table are safe for GLES 3.x virpipe contexts. Consider adding `GL_MAX_CLIP_DISTANCES` to the removal list as a precaution, or at least verify its enum value matches what the Mesa headers define.

### SC-6: Quality Gate G0 uses `run-tests.sh --quick` but `--quick` runs `--all`

**Phase**: 0, Quality Gate G0
**Issue**: The quality gate command is:
```bash
adb shell "run-as com.termux bash -lc '~/gl-tests/scripts/run-tests.sh --quick'"
```
But looking at `run-tests.sh`, `--quick` mode runs the harness with `--all` (line 289), which executes ALL 9 modules. If Phase 0's goal is just to verify the SIGSEGV fix, running all 9 modules is unnecessary and slow. Module 4+ will fail (FBO is still broken at this point) and their failures could obscure the Phase 0 pass signal.

**Consequence**: Noisy output makes it harder to verify Phase 0 succeeded. Also, if any module other than 1 crashes, the quality gate "no SIGSEGV" criterion is ambiguous.

**Fix**: Quality Gate G0 should use `--module 1` first to verify the crash fix, then `--all` to verify no SIGSEGV across all modules. The gate should specify that FBO-related module FAILs are expected and acceptable at this phase.

### SC-7: Phase 2 changes `GL_RGBA8` to `GL_RGBA` but Module 5 and 8 still use `GL_RGBA8`

**Phase**: 2, Step 2.2
**Issue**: The plan changes `GL_RGBA8` to `GL_RGBA` in `create_fbo()` (Step 2.2) which affects the color attachment of every FBO created via `create_fbo()`. However, Module 5 (line 892) and Module 8 (line 1282) directly call `pglTexImage3D` with `GL_RGBA8` for their texture arrays. If `GL_RGBA8` is problematic for virglrenderer, it could also cause issues in texture array creation — but these are textures, not FBO attachments, so the behavior may differ.

**Consequence**: If `GL_RGBA8` is fundamentally broken in virglrenderer (not just for FBO attachments), Modules 5 and 8 will also fail even after Phase 2 fixes.

**Fix**: Add a note that if FBO fixes succeed but Modules 5/8 still fail, consider changing their `GL_RGBA8` texture array creation to use `GL_RGBA` as well.

### SC-8: `MESA_DEBUG=1` left in Phase 2 creates massive log output

**Phase**: 2, Step 2.4
**Issue**: The plan adds `MESA_DEBUG=1` in Phase 2 and removes it in Phase 6 (Step 6.4). But between Phase 2 and Phase 6, Phases 3, 4, and 5 all run tests. `MESA_DEBUG=1` combined with the existing `MESA_LOG_LEVEL=debug` and `LIBGL_DEBUG=verbose` will produce extremely large mesa.log files. On a device with limited storage (the plan checks for 500MB minimum), this could fill the tmp directory.

**Consequence**: Disk space exhaustion during Phases 3-5, causing test failures or corrupted results.

**Fix**: Either remove `MESA_DEBUG=1` immediately after Phase 2 diagnosis is complete (before Phase 3), or add a disk space check before each phase.

### SC-9: `probe_fbo_capability()` uses `GL_RGBA` but main `create_fbo()` uses `GL_RGBA8` (pre-Phase 2)

**Phase**: 2, Steps 2.1 and 2.5
**Issue**: The plan adds `probe_fbo_capability()` in Step 2.1 (which uses `GL_RGBA` unsized format) and calls it in `main()` in Step 2.5 (which is Phase 2). But `create_fbo()` isn't changed from `GL_RGBA8` to `GL_RGBA` until Step 2.2. The Phase 2 implementation checklist orders them as: "Add `g_fbo_works` global and `probe_fbo_capability()`" (Step 2.1), then "Change `GL_RGBA8` to `GL_RGBA`" (Step 2.2). If implemented in order, there's a brief moment where the probe uses `GL_RGBA` (works) but `create_fbo` uses `GL_RGBA8` (broken) — and `g_fbo_works` would report WORKS even though actual FBO creation with `GL_RGBA8` would fail.

**Consequence**: False positive from probe: `g_fbo_works = 1` but actual FBOs created by modules still fail because they go through `create_fbo()` which still uses `GL_RGBA8`.

**Fix**: Steps 2.1 and 2.2 must be deployed together, not separately. Add a note in the plan that all Phase 2 C changes must be compiled and deployed as a single unit.

---

## NITPICK (5 findings)

### NP-1: Plan references line numbers that may drift

**Phase**: All
**Issue**: The plan references specific line numbers (e.g., "Line 295", "Lines 1585-1589", "Lines 244-248"). After Phase 0 changes the code, these line numbers shift. Phase 1 and later reference the original line numbers.

**Fix**: Use search patterns or context-based references instead of line numbers, or note that line numbers are approximate and refer to the pre-modification state.

### NP-2: `depth32f` parameter kept "for future cleanup" — dead code

**Phase**: 2, Step 2.3
**Issue**: The plan says "The `depth32f` parameter to `create_fbo()` becomes unused after this change. Keep it in the signature to avoid updating all call sites." This creates dead code that a code review agent will flag.

**Fix**: Either update all call sites (all pass `0` already, so just remove the parameter) or add a `(void)depth32f;` statement to suppress unused parameter warnings.

### NP-3: Quality Gate G1 tar extraction may fail on Windows

**Phase**: 1, Quality Gate G1
**Issue**: The quality gate commands use:
```bash
tar xzf /tmp/gl-results.tar.gz -C /tmp/gl-results/
```
On the Windows host (Git Bash), `/tmp` maps to a Git-Bash-specific temporary directory, not a real `/tmp`. The `adb shell` piping also differs between cmd.exe and Git Bash.

**Fix**: Use Windows-compatible paths in quality gate commands, e.g., `C:/tmp/gl-results/` or document that Git Bash paths should be used.

### NP-4: `PERF_HALF_WIDTH/HEIGHT` constants not affected by fallback

**Phase**: 3, Step 3.2
**Issue**: Module 9's half-resolution FBO test uses the constants `PERF_HALF_WIDTH` (128) and `PERF_HALF_HEIGHT` (128). The fallback plan says to "skip the half-res test entirely if FBO is broken". But the full-size rendering in Module 9 also uses FBO — if FBO is broken, the entire module should use the default framebuffer, and the half-res comparison becomes meaningless.

**Fix**: Clarify that Module 9's fallback should skip ALL FBO-based rendering (including the full-size FBO) and render to the default framebuffer for the performance baseline. The half-res comparison should be skipped entirely.

### NP-5: Inconsistent module naming in checklist

**Phase**: Implementation checklist
**Issue**: The checklist says "Reorder `--all` module sequence (M1, M2, M3, M7, M4, M5, M6, M8, M9)" but the code in Step 2.5 shows Module 4 called with argument `"a"` while the checklist just says M4. This is minor but could cause confusion during implementation.

**Fix**: Make the checklist match the code: "M1, M2, M3, M7, M4a, M5, M6, M8, M9".

---

## Cross-Cutting Concerns

### CC-1: No mention of CRLF protection

The plan modifies `run-tests.sh` and potentially `launch-runelite.sh`. Per `.claude/rules/shell-scripts.md` Hard Rule #7 and CLAUDE.md Solved Problem #7, Windows git CRLF breaks shebangs on Termux/Linux. The plan should note that `.gitattributes` with `eol=lf` must cover these files, and any editing on Windows must preserve LF line endings.

### CC-2: Plan does not account for virgl server restart between phases

When changing env vars (Phase 1) or GL configuration, the virgl server may cache state from previous runs. The plan should note that between phases, the virgl server should be killed and restarted to ensure a clean state. The `run-tests.sh` script handles this for each run, but manual testing during development (Phase 4 especially) may not.

### CC-3: `destroy_fbo` in `probe_fbo_capability()` doesn't exist

The probe function in Step 2.1 calls `pglDeleteFramebuffers(1, &fbo)` and `glDeleteTextures(1, &colorTex)` directly (not via `destroy_fbo()`). This is fine and actually correct since the probe doesn't create a depth attachment. But it means the probe's cleanup path diverges from the standard `destroy_fbo()` path. If `destroy_fbo` is later modified (e.g., per MF-2 fix), the probe won't be affected, which is correct but should be documented.

---

## Summary of Required Actions

| ID | Severity | Phase | Action |
|----|----------|-------|--------|
| MF-1 | MUST-FIX | 2.3 | Add renderbuffer function pointer types, statics, and resolution |
| MF-2 | MUST-FIX | 2.3 | Fix `destroy_fbo` to handle renderbuffer vs texture depth |
| MF-3 | MUST-FIX | 1/5 | Validate 4.3COMPAT doesn't break RuneLite GPU plugin |
| MF-4 | MUST-FIX | 0.2 | Guard `fflush(g_log_file)` with NULL check |
| MF-5 | MUST-FIX | 1/2 | Acknowledge `set -e` exception for `run-tests.sh` |
| MF-6 | MUST-FIX | 1.1 | Verify `MESA_EXTENSION_OVERRIDE` reaches virpipe layer |
| MF-7 | MUST-FIX | 3.3 | Move `diag1` to function scope |
| SC-1 | SHOULD | 2.1 | Document virgl server crash risk from probe |
| SC-2 | SHOULD | 3.1 | Minimize visible window impact |
| SC-3 | SHOULD | All | Add rollback strategy per phase |
| SC-4 | SHOULD | 1/5 | Verify env var propagation in both scripts |
| SC-5 | SHOULD | 0.1 | Audit remaining int_queries for GLES safety |
| SC-6 | SHOULD | 0 | Use `--module 1` for G0, not `--quick` |
| SC-7 | SHOULD | 2.2 | Note GL_RGBA8 in Modules 5/8 texture arrays |
| SC-8 | SHOULD | 2.4 | Remove MESA_DEBUG=1 earlier than Phase 6 |
| SC-9 | SHOULD | 2.1/2.2 | Deploy all Phase 2 C changes as single unit |
| NP-1 | NITPICK | All | Use context-based refs not line numbers |
| NP-2 | NITPICK | 2.3 | Remove dead `depth32f` parameter |
| NP-3 | NITPICK | 1 | Use Windows-compatible paths in quality gates |
| NP-4 | NITPICK | 3.2 | Clarify Module 9 full fallback behavior |
| NP-5 | NITPICK | Checklist | Match checklist to code (M4a not M4) |

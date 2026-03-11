# Dependency Graph: VirGL Test Pipeline

**Date**: 2026-03-09
**Spec**: specs/2026-03-09-virgl-test-pipeline-spec.md

## Blast Radius

### Direct: 14 new files (0 modified existing)

All in `runelite-tablet/gl-tests/` (standalone, not in app source):

| File | Type | Risk |
|------|------|------|
| `src/gl_test_harness.c` | New | Low — standalone C program |
| `src/gl_test_log.h` | New | Low — header, no external deps |
| `src/fix_inject_clipcontrol.c` | New | Low — LD_PRELOAD shim |
| `src/fix_flip_depth.c` | New | Low — LD_PRELOAD shim |
| `src/test_shaders.h` | New | Low — GLSL string constants |
| `src/stb_image_write.h` | New (vendored) | Low — third-party, read-only |
| `scripts/deploy.sh` | New | Low — adb push wrapper |
| `scripts/install-deps.sh` | New | Medium — apt-get in proot |
| `scripts/build.sh` | New | Low — gcc in proot |
| `scripts/run-tests.sh` | New | Medium — orchestrator |
| `scripts/install-piglit.sh` | New | Medium — builds from source |
| `README.md` | New | Low — documentation |

### Modified: 1 existing file

| File | Change | Risk |
|------|--------|------|
| `.gitattributes` (repo root) | Add 2 lines for C/H eol=lf | Low — additive only |

### Dependent: 0 files

No existing code references or imports the test pipeline.

### Test: 0 existing test files

The test pipeline IS the test infrastructure. Self-contained.

### Cleanup: 0 files

No documentation, config, or state files need updating (standalone tool).

## Cross-Package Dependencies

**NONE.** The test pipeline:
- Does not import any Kotlin code
- Does not reference any Android app components
- Does not modify any existing shell scripts
- Does not add any Gradle dependencies
- Is not built by the Android build system

## Risk Classification

| Category | Risk | Rationale |
|----------|------|-----------|
| Overall | **LOW** | All new files, zero existing code modified, zero cross-package deps |
| Shell scripts | **MEDIUM** | Enter proot, manage VirGL server lifecycle — same patterns as existing scripts |
| C source | **LOW** | Compiled and run inside proot only, not part of app |
| .gitattributes | **LOW** | Additive change, well-understood |

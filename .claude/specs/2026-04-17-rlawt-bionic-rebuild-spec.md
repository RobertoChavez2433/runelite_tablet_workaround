# rlawt Bionic Rebuild — Verification-Gate Spec

**Date**: 2026-04-17
**Parent Plan**: `.claude/plans/2026-04-17-rlawt-bionic-rebuild.md`
**Status**: Active — Task 21 execution gate
**Purpose**: every item below is a checkbox with a concrete command and expected output. If every box is ticked, Task 21 is complete. If any box cannot be ticked, we iterate until it can.

**Scope rule**: this spec covers Task 21 only (the rebuild). Downstream Phases 2.1 / 2.2 / 3 have their own acceptance — listed here only as handoff contract.

---

## Notation

- `[ ]` — open
- `[x]` — closed (evidence recorded)
- `GATE:` — the verification that must pass before the item flips to `[x]`
- `EVIDENCE:` — where the proof lives after the fact (file path, log snippet, or command transcript)

---

## U — Open Unknowns (recon; must close before 21A builds)

- [ ] **U1. Upstream rlawt build system identified**
  - GATE: `third_party/rlawt/CMakeLists.txt` (or equivalent) exists and is audited
  - EVIDENCE: `third_party/rlawt/AUDIT.md` §Build System

- [ ] **U2. Source tag matching `rlawt-1.8.jar` resolved**
  - GATE: `git -C third_party/rlawt describe --tags` returns `rlawt-1.8` or a commit pinned in AUDIT.md with justification (e.g., jar manifest SHA match)
  - EVIDENCE: `third_party/rlawt/AUDIT.md` §Tag Match

- [ ] **U3. JAWT header source resolved**
  - GATE: exact path of `jawt.h` + `jawt_md.h` used for build is documented
  - EVIDENCE: `third_party/rlawt/AUDIT.md` §JAWT Headers, with ABI justification (matches openjdk-21-x on device)

- [ ] **U4. RuneLite classpath reachability from Termux-native** (deferred to Phase 2.1)
  - GATE: not blocking for Task 21. Documented as open question in plan Risk R4.

---

## A — Phase 21A — Vendor + audit

- [ ] **A1. Upstream rlawt cloned**
  - GATE: `third_party/rlawt/` exists; contains `.c`/`.cpp`/`.h`/`CMakeLists.txt`/`LICENSE`
  - EVIDENCE: directory listing in AUDIT.md

- [ ] **A2. Correct tag checked out**
  - GATE: U2 satisfied
  - EVIDENCE: AUDIT.md records tag/commit + matches jar manifest

- [ ] **A3. Source audited for glibc-specific code**
  - GATE: grep for `<malloc.h>`, `<features.h>`, `<sys/epoll.h>`, `gnu_*`, `__GLIBC__` returns either zero hits or each hit is justified as Bionic-safe
  - EVIDENCE: AUDIT.md §glibc Scan with grep transcript

- [ ] **A4. Symbol-to-provider map drafted**
  - GATE: every expected undefined symbol from rlawt sources is mapped to a target NEEDED .so (libjawt/libGL/libX11/libc/libdl)
  - EVIDENCE: AUDIT.md §Symbol Map

- [ ] **A5. CMake link-line summarized**
  - GATE: AUDIT.md §CMake records original `target_link_libraries` + any `-l` flags + include paths
  - EVIDENCE: AUDIT.md §CMake

---

## B — Phase 21B — NDK toolchain + headers

- [ ] **B1. NDK version pinned**
  - GATE: `scripts/build-rlawt-bionic.sh` hard-codes `ANDROID_NDK_ROOT=C:/Users/rseba/AppData/Local/Android/Sdk/ndk/28.2.13676358`
  - EVIDENCE: file exists; head of file shows pin

- [ ] **B2. NDK toolchain file resolvable**
  - GATE: `test -f $ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake`
  - EVIDENCE: command success in build script dry-run

- [ ] **B3. JAWT headers harvested**
  - GATE: `third_party/rlawt/include/jawt/jawt.h` and `jawt_md.h` exist; match openjdk-21 upstream version
  - EVIDENCE: `head -5` of each file matches expected copyright/version

- [ ] **B4. X11 headers harvested from device**
  - GATE: `third_party/rlawt/include/X11/Xlib.h` exists (plus dependencies rlawt uses)
  - EVIDENCE: `adb pull` transcript + directory listing

- [ ] **B5. GL headers harvested from device**
  - GATE: `third_party/rlawt/include/GL/glx.h`, `gl.h`, `glext.h` exist
  - EVIDENCE: directory listing

- [ ] **B6. Termux Bionic sysroot stubs pulled**
  - GATE: `third_party/rlawt/sysroot-stubs/libjawt.so`, `libGL.so`, `libX11.so` exist; `file <each>` shows `ELF 64-bit LSB shared object, ARM aarch64` AND `readelf -d <each> | grep SONAME` shows no `.6` suffix
  - EVIDENCE: readelf transcript saved to `third_party/rlawt/sysroot-stubs/SONAME-check.txt`

- [ ] **B7. Build script runs cmake configure without error**
  - GATE: `bash scripts/build-rlawt-bionic.sh --configure-only` exits 0
  - EVIDENCE: stdout shows `-- Configuring done` and `-- Generating done`

---

## C — Phase 21C — Link-line correctness (CRITICAL)

- [ ] **C1. librlawt.so builds**
  - GATE: `bash scripts/build-rlawt-bionic.sh` exits 0; output at `third_party/rlawt/build-bionic/librlawt.so`
  - EVIDENCE: build log + `ls -la` of output

- [ ] **C2. NEEDED entries match target**
  - GATE: `readelf -d librlawt.so | grep NEEDED` shows exactly `libjawt.so`, `libGL.so`, `libX11.so`, `libc.so`, `libdl.so` (order irrelevant; NO extras, NO `.6` suffixes, NO `ld-linux-aarch64.so.1`)
  - EVIDENCE: `third_party/rlawt/build-bionic/readelf-dynamic.txt`

- [ ] **C3. No VERNEED / VERNEEDNUM / VERSYM**
  - GATE: `readelf -d librlawt.so` shows no `VERNEED`, `VERNEEDNUM`, or `VERSYM` tag. `readelf -S librlawt.so` shows no `.gnu.version_r` or `.gnu.version` sections.
  - EVIDENCE: readelf-dynamic.txt + `readelf-sections.txt`

- [ ] **C4. Soname is `librlawt.so`**
  - GATE: `readelf -d librlawt.so | grep SONAME` shows `SONAME librlawt.so`
  - EVIDENCE: readelf-dynamic.txt

- [ ] **C5. JNI exports visible**
  - GATE: `nm -D --defined-only librlawt.so | grep -E '^[0-9a-f]+ T (JNI_OnLoad|Java_net_runelite_rlawt_AWTContext_)'` returns ≥ 1 match
  - EVIDENCE: `third_party/rlawt/build-bionic/nm-exports.txt`

---

## D — Phase 21D — Undefined-symbol audit

- [ ] **D1. Undefined symbol count**
  - GATE: `nm -u librlawt.so | wc -l` ≤ 30
  - EVIDENCE: `third_party/rlawt/build-bionic/undef-symbols.txt` + count

- [ ] **D2. Every non-weak symbol has a resolvable provider**
  - GATE: for each of the 5 libc primitives, 11 GLX/GL, 6 X11, and 1 JAWT symbols: `objdump -T <provider.so> | grep <sym>` on device returns a `.dynsym` hit
  - EVIDENCE: `third_party/rlawt/build-bionic/symbol-resolution-map.md` with one row per symbol

- [ ] **D3. Weak symbols cataloged, not blockers**
  - GATE: `__cxa_finalize`, `__gmon_start__`, `_ITM_*` identified as weak in `nm -u` output (lowercase `w` or `v` marker) and marked as non-blocking
  - EVIDENCE: symbol-resolution-map.md footnotes

---

## E — Phase 21E — On-device load test

- [ ] **E1. .so pushed to device rlawt-test dir**
  - GATE: `adb shell "run-as com.termux ls -la \$HOME/rlawt-test/librlawt-bionic.so"` succeeds, size > 0
  - EVIDENCE: shell transcript

- [ ] **E2. MiniRlawtLoad prints SUCCESS**
  - GATE: `adb shell "run-as com.termux sh -c 'cd \$HOME/rlawt-test && java MiniRlawtLoad librlawt-bionic.so'"` prints `SUCCESS: librlawt.so loaded` (substring match) and exits 0
  - EVIDENCE: stdout capture to `runelite-tablet/docs/logs/rlawt-bionic-load-success.log`

- [ ] **E3. No UnsatisfiedLinkError in stderr**
  - GATE: stderr from E2 contains no `UnsatisfiedLinkError` and no `dlopen failed`
  - EVIDENCE: same log file

---

## F — Phase 21F — Repackage JAR

- [ ] **F1. rlawt-1.8.jar original obtained**
  - GATE: `/tmp/rlawt-1.8.jar` exists on host; SHA256 recorded
  - EVIDENCE: `runelite-tablet/app/libs/rlawt-1.8-original.sha256`

- [ ] **F2. .so replaced, everything else byte-identical**
  - GATE: `unzip -l rlawt-1.8-bionic.jar` shows same file list + sizes as original, modulo the one .so
  - EVIDENCE: `diff <(unzip -l rlawt-1.8.jar) <(unzip -l rlawt-1.8-bionic.jar)` — only the librlawt.so line should differ in size

- [ ] **F3. librlawt.so inside jar matches our build**
  - GATE: `unzip -p rlawt-1.8-bionic.jar net/runelite/rlawt/linux-aarch64/librlawt.so | sha256sum` equals the sha256 of `third_party/rlawt/build-bionic/librlawt.so`
  - EVIDENCE: transcript

- [ ] **F4. AWTContext.class unchanged**
  - GATE: `unzip -p rlawt-1.8-bionic.jar net/runelite/rlawt/AWTContext.class | sha256sum` equals same sha256 of the original jar's AWTContext.class
  - EVIDENCE: transcript

- [ ] **F5. JAR committed under runelite-tablet/app/libs/**
  - GATE: `runelite-tablet/app/libs/rlawt-1.8-bionic.jar` exists in working tree
  - EVIDENCE: `ls -la` + size

---

## G — Phase 21G — Integration smoke (MiniAwtContext)

- [ ] **G1. MiniAwtContext.java compiles against repackaged jar**
  - GATE: `javac -cp rlawt-1.8-bionic.jar third_party/rlawt/test/MiniAwtContext.java` exits 0
  - EVIDENCE: build log

- [ ] **G2. GL context created on device**
  - GATE: on-device run prints a non-empty `GL_VERSION` string and `eglGetCurrentContext != 0`
  - EVIDENCE: `runelite-tablet/docs/logs/rlawt-bionic-awt-smoke.log`

- [ ] **G3. No crash / no dangling X connections**
  - GATE: `adb shell ps -A | grep termux-x11` shows clean state post-run (no orphaned server); `logcat -b crash -d` shows no rlawt-related signal
  - EVIDENCE: same smoke log

---

## H — Phase 21H — Commit + vendor

- [ ] **H1. third_party/rlawt/ committed**
  - GATE: working tree clean after commit; `git log -1 --stat` shows added files under `third_party/rlawt/`
  - EVIDENCE: `git log` entry

- [ ] **H2. scripts/build-rlawt-bionic.sh committed + executable**
  - GATE: file exists, `bash scripts/build-rlawt-bionic.sh --help` (or dry-run) works from a clean checkout
  - EVIDENCE: file tracked in git

- [ ] **H3. rlawt-1.8-bionic.jar committed**
  - GATE: `git ls-files runelite-tablet/app/libs/rlawt-1.8-bionic.jar` non-empty
  - EVIDENCE: git transcript

- [ ] **H4. _state.md updated**
  - GATE: `.claude/autoload/_state.md` HOT CONTEXT block reflects Task 21 complete + new entry point at Phase 2.1; Task 21 removed from active list
  - EVIDENCE: diff of _state.md

- [ ] **H5. Commit message passes hook**
  - GATE: commit hook (`scripts/git/commit-msg`) accepts. Subject `build(native): ...`; body ≥ 20 chars; `Reason:` trailer present
  - EVIDENCE: commit lands

---

## S — Exit Criteria (S21-FINAL)

- [ ] **S1.** MiniRlawtLoad prints `SUCCESS: librlawt.so loaded` under Termux-native openjdk-21-x with no proot (E2 satisfied)
- [ ] **S2.** MiniAwtContext successfully calls `createGLContext()` against a live Termux X server (G2 satisfied)
- [ ] **S3.** `readelf -d librlawt.so`: Bionic-only NEEDED, no VERNEED/VERSYM (C2 + C3 satisfied)
- [ ] **S4.** `nm -u librlawt.so` ≤ 30 symbols, all resolvable on device (D1 + D2 satisfied)
- [ ] **S5.** All Task 21 artifacts committed with passing hook (H1-H5 satisfied)

When S1–S5 are all `[x]`, Task 21 is complete. Open Phase 2.1 as next entry point.

---

## Spec Review Checklist (for Step 4 of the workflow)

After implementation, walk every item in this spec. For each:

1. Did the gate fire? (Yes/no)
2. Is the EVIDENCE artifact actually present at the named path?
3. If the gate mapping to the plan is ambiguous, clarify the spec item or the plan.

If any item is ambiguous, unverifiable, or missing — iterate. The spec is the contract; if it's weaker than the plan, tighten the spec.

---

## Handoff Contract (Phases 2.1 / 2.2 / 3 — not Task 21)

For visibility only. These are separate specs after Task 21.

- **Phase 2.1 exit**: `launch-runelite-native.sh` ships in APK assets; RLT_NATIVE_TERMUX=1 gate reaches `java` directly without proot; JVM starts and loads RL mainClass.
- **Phase 2.2 exit**: RuneLiteSessionService picks native vs proot path based on flag; ScriptManager re-deploy bug fixed with checksum.
- **Phase 3 exit**: A/B at Varrock East Bank shows ≥ 30 FPS on native path (ptrace removed). Spike goal ≥ 60. S-FINAL target ≥ 100.

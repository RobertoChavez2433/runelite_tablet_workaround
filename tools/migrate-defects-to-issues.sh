#!/usr/bin/env bash
# One-shot migration of .claude/defects/* + .claude/logs/defects-archive.md to GitHub issues.
# Per the audit (session 72):
#   1 OPEN (security)  -> open with p1
#   3 UNKNOWN          -> open with needs-repro + p2
#   51 RESOLVED        -> open + immediately close with historical label
#   2 STALE            -> skipped (code deleted)
#
# Rerun-safe: gh issue list is checked before creation; skip if title already exists.

set -eu

REPO="RobertoChavez2433/runelite_tablet_workaround"
MIG_DATE="$(date +%Y-%m-%d)"

# Fetch existing titles once; if a title matches, skip.
existing=$(gh issue list --repo "$REPO" --state all --limit 500 --json title --jq '.[].title')

created=0
skipped=0
failed=0

make_issue() {
    # args: type scope priority status subject body_text reason refs [follow_up]
    local type="$1" scope="$2" priority="$3" status="$4" subject="$5" body="$6" reason="$7" refs="$8" followup="${9:-}"

    local title="$type($scope): $subject"

    if printf '%s\n' "$existing" | grep -Fxq "$title"; then
        echo "  [skip] exists: $title"
        skipped=$((skipped+1))
        return 0
    fi

    local labels="type:$type,scope:$scope,$priority"
    case "$status" in
        historical) labels="$labels,historical" ;;
        needs-repro) labels="$labels,needs-repro" ;;
    esac

    local trailers="Reason: $reason"
    if [ -n "$refs" ];     then trailers="$trailers
Refs: $refs"; fi
    if [ -n "$followup" ]; then trailers="$trailers
Follow-up: $followup"; fi

    local full_body="$body

$trailers"

    local url
    url=$(printf '%s' "$full_body" | gh issue create \
        --repo "$REPO" \
        --title "$title" \
        --label "$labels" \
        --body-file - 2>&1) || { echo "  [fail] $title -> $url"; failed=$((failed+1)); return 0; }

    echo "  [open] $url"

    if [ "$status" = "historical" ]; then
        local num="${url##*/}"
        gh issue close "$num" --repo "$REPO" \
            --reason completed \
            --comment "Migrated from .claude/defects/ on ${MIG_DATE}. Prevention already applied in current code; closing as historical."  >/dev/null \
            || echo "    [warn] close failed for #$num"
    fi

    created=$((created+1))
}

echo "==> migrating active auth defects"

make_issue fix auth p3 historical \
  "Jagex Step 1 tokens were discarded during Step 2 consent" \
  "**Problem:** GeckoAuthActivity.handleStep2Redirect() returned accessToken=null, refreshToken=null. Step 1 TokenResponse fields were not saved before loading the Step 2 consent URL. SetupViewModel.handleAuthResult for Jagex accounts never called storeTokens(). Env file ended up without JX_ACCESS_TOKEN.

**Decision:** Save Step 1 TokenResponse fields in GeckoAuthActivity instance variables before navigating to Step 2. Pass them through finishWithSuccess(). ViewModel must call storeTokens() for every provider, not only RuneScape legacy.

**Tradeoff:** Accepted a small amount of instance-field state on the Activity for the duration of the Step 2 flow. Rejected writing tokens to disk mid-flow.

**Evidence (audit):** OAuthFlowCoordinator.kt:109-115 saves step1 tokens; parseStep2Redirect:155 returns them; GeckoAuthActivity.finishWithSuccess:120-130 forwards them; AuthCoordinator.handleAuthResult:85-88 calls storeTokens for all providers." \
  "Jagex login silently failed because Step 1 tokens were dropped during consent" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt, runelite-tablet/app/src/main/java/com/runelitetablet/auth/OAuthFlowCoordinator.kt, runelite-tablet/app/src/main/java/com/runelitetablet/setup/AuthCoordinator.kt"

make_issue fix auth p3 historical \
  "HTTP 400 invalid_grant was not treated as NeedsLogin in refresh" \
  "**Problem:** refreshIfNeeded() only treated HTTP 401 as AuthResult.NeedsLogin. HTTP 400 invalid_grant (dead/revoked refresh token) was treated as NetworkError and the app continued with stale credentials instead of triggering GeckoView re-auth.

**Decision:** Treat both HTTP 400 and 401 from token refresh as NeedsLogin, because both indicate the refresh token is permanently unusable and the user must re-authenticate.

**Tradeoff:** A real transient 400 (very rare) forces re-auth instead of a silent retry. Accepted because silent-retry on invalid_grant is worse.

**Evidence (audit):** AuthCoordinator.kt:201 maps both 401 and 400 to NeedsLogin." \
  "stale refresh tokens no longer silently failed to produce a usable session" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/AuthCoordinator.kt"

make_issue fix scripts p3 historical \
  "repository2 client jars were frozen when launcher was bypassed" \
  "**Problem:** launch-runelite.sh had two launch paths: Path A (exec java -cp from repository2/) and Path B (run RuneLite.jar launcher). Once repository2/ was populated, Path A always fired and the launcher never ran again, so client jars stopped auto-updating. OSRS weekly revision bumps then produced LOGGING_IN -> LOGIN_SCREEN with no error, indistinguishable from auth failure.

**Decision:** Delete the repository2/ cache on update-runelite.sh so the next launch is forced through the launcher path. Also gate launch-path selection behind an explicit RUNELITE_LAUNCH_MODE env.

**Tradeoff:** First post-update launch is slower (launcher bootstraps fresh). Accepted because silent-revision-freeze is a P0 bug.

**Evidence (audit):** update-runelite.sh:98-99 rm -rf /root/.runelite/repository2; launch-runelite.sh:881 uses RUNELITE_LAUNCH_MODE env." \
  "weekly OSRS revisions were silently frozen because Path A never re-ran the launcher" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh, runelite-tablet/app/src/main/assets/scripts/update-runelite.sh"

make_issue fix auth p3 historical \
  "Jagex accounts received 5 env vars instead of 3" \
  "**Problem:** launch-runelite.sh and SetupViewModel.performLaunch() passed all 5 env vars (JX_SESSION_ID, JX_CHARACTER_ID, JX_DISPLAY_NAME, JX_ACCESS_TOKEN, JX_REFRESH_TOKEN) for Jagex accounts. Only the first three are valid for Jagex; the other two belong to legacy RuneScape accounts and caused the client to attempt legacy auth paths.

**Decision:** Gate env var writing on account type. Jagex: write only the three identifiers. Legacy RuneScape: write all five.

**Tradeoff:** One more branch in the env writer. Accepted for correctness against RuneLite JagexAccountAuthenticator contract.

**Evidence (audit):** LaunchEnvDeployer.kt:22-24 writes only the three Jagex vars; launch-runelite.sh:507-510 forwards only those three; no JX_ACCESS_TOKEN for Jagex." \
  "Jagex accounts were being handed legacy-client env vars, confusing the auth path" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/LaunchEnvDeployer.kt, runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix scripts p3 historical \
  "hardcoded launcher version pin was stale" \
  "**Problem:** launch-runelite.sh passed -Drunelite.launcher.version=2.7.6 hardcoded. The real launcher had already moved to 2.7.7 and later versions introduced security gates; reporting a stale version may have caused version-gated behavior to regress.

**Decision:** Remove the hardcoded -Drunelite.launcher.version flag entirely. Let the launcher JAR report its own version via META-INF/MANIFEST.MF.

**Tradeoff:** No longer forcing a pinned value. Accepted: the correct value is whatever the launcher jar actually is.

**Evidence (audit):** grep for runelite.launcher.version in launch-runelite.sh returns no matches; the pin is gone." \
  "hardcoded launcher version drifted behind real releases and risked version-gated regressions" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

echo "==> migrating open security defect"

make_issue security auth p1 open \
  "shellEscape is missing '!' and does not strip newline/CR/null" \
  "**Problem:** LaunchEnvDeployer.shellEscape still uses a double-quote escape covering only \\, \", \$, and backtick. The documented prevention also requires escaping '!' (bash history expansion) AND stripping \\n, \\r, \\0 from credential values, because a newline inside a double-quoted credential breaks out of the string entirely and enables shell injection when the env file is source'd.

**Decision:** Add '!' to the escaped metachar set and strip control bytes (\\n, \\r, \\0) from every value before writing. Alternatively, switch to bash-native printf %q.

**Tradeoff:** printf %q is the cleanest fix but requires the env deployer to emit bash directly instead of being shell-agnostic. Either path is fine; the current code satisfies neither.

**Evidence (grep):** LaunchEnvDeployer.kt:50-54 escapes only \\, \", \$, backtick. No '!' handling, no newline stripping. Runtime mitigation exists because launch-runelite.sh:507-510 uses printf %q on read-back, but the Kotlin-side escape still violates the documented prevention and could be reused from a path that does NOT call printf %q." \
  "credential values containing '!' or a newline can inject shell commands when the env file is sourced" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/LaunchEnvDeployer.kt:50-54"

echo "==> migrating active setup defects"

make_issue fix tests p2 needs-repro \
  "UnsafeHelper.allocate cannot instantiate abstract classes" \
  "**Problem:** UnsafeHelper.allocate(android.content.Context::class.java) fails at runtime because Context is an abstract class. Tests using this helper for any abstract Android framework type crash on allocation.

**Decision:** Always pass a concrete subclass (for example android.app.Application instead of android.content.Context). Consider adding an assertion inside UnsafeHelper that rejects abstract classes with a clear message.

**Tradeoff:** A runtime assertion inside the helper is loud but catches every future misuse. Rejected: generic type-erased validation in Kotlin source is not worth the complexity.

**Evidence (audit):** UnsafeHelper.kt itself has no guardrail. Call-site audit was not performed in session 72; marked needs-repro because we cannot confirm whether any current caller still passes an abstract class." \
  "every future test author is one bad argument away from a confusing allocation crash" \
  "runelite-tablet/app/src/test/java/com/runelitetablet/testutil/UnsafeHelper.kt" \
  "audit every call to UnsafeHelper.allocate() and confirm the argument is a concrete class"

make_issue refactor setup p3 historical \
  "PermissionHandler needed an interface to be test-fakeable" \
  "**Problem:** Extracting PermissionHandler as a plain Kotlin class made it impossible to subclass or fake in tests because Kotlin classes are final by default.

**Decision:** Extract a domain interface (PermissionChecker) and have the concrete PermissionHandler implement it. Tests depend on the interface and use a fake.

**Tradeoff:** One extra interface file. Accepted because it is the minimum indirection required for test replaceability.

**Evidence (audit):** domain/setup/PermissionChecker.kt exists as an interface; setup/PermissionHandler.kt is declared open class and implements the interface." \
  "we could not fake PermissionHandler in unit tests without an interface boundary" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/domain/setup/PermissionChecker.kt, runelite-tablet/app/src/main/java/com/runelitetablet/setup/PermissionHandler.kt"

make_issue fix scripts p3 historical \
  "RuneLite launcher JVM flags did not propagate to the client" \
  "**Problem:** java -Xmx2g -Dsun.java2d.uiScale=2.0 -jar RuneLite.jar set flags on the launcher process only. The launcher spawned a child JVM via JvmLauncher with its own args (-Xmx768m, no uiScale), so every custom flag was silently lost by the client.

**Decision:** Use --scale N on the launcher command line (propagates -Dsun.java2d.uiScale to the client). Use the RUNELITE_VMARGS env var for -Xmx/-XX flags (appended after launcher args, last -Xmx wins). Never put JVM flags on the java -jar RuneLite.jar line expecting them to reach the client.

**Tradeoff:** We cannot pass arbitrary -D flags to the client without them surviving the launcher boundary. Accepted; --scale + RUNELITE_VMARGS covers the real needs.

**Evidence (audit):** launch-runelite.sh:936 uses SCALE_FLAG=--scale; :961 sets RUNELITE_VMARGS; :1029 invokes java without a CLI -Xmx." \
  "tablet-tuned JVM flags were being thrown away by the launcher child JVM" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix setup p3 historical \
  "reconcileWithMarkers ABSENT branch must clear stateStore" \
  "**Problem:** SetupOrchestrator.reconcileWithMarkers() downgraded step status to Pending when the on-disk marker was ABSENT, but it did not clear the stateStore isCompleted flag. executeStep() checked stateStore first and no-oped, so the downgraded step never actually re-ran.

**Decision:** Call stateStore.clearCompleted(key) alongside updateStepStatus(index, Pending) in the ABSENT branch.

**Tradeoff:** One extra write per reconcile. Negligible vs the alternative of silent step skipping.

**Evidence (audit):** SetupOrchestrator.kt:196 updateStepStatus(i, StepStatus.Pending); stateStore.clearCompleted(key); both calls present." \
  "ABSENT reconciliation silently skipped the step because stateStore was not cleared" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt"

make_issue fix setup p3 historical \
  "resetSetup and runSetupForHealth left orchestrator state stale" \
  "**Problem:** resetSetup() cleared stateStore but left SetupOrchestrator's _permissionPhase, _awaitingPermissionCompletion, and failedStepIndex stale. runSetupForHealth() did not reset setupStarted, so startSetup() no-oped on re-entry.

**Decision:** Add orchestrator.resetState() that clears the internal state fields, call it from resetSetup. Reset setupStarted.set(false) from runSetupForHealth.

**Tradeoff:** Orchestrator has to expose a resetState method. Accepted: the alternative is a hidden mismatch between stateStore and orchestrator memory.

**Evidence (audit):** SetupOrchestrator.kt:48-53 defines resetState(); SetupViewModel.kt:115-116 calls orchestrator.resetState(); :122-124 resets setupStarted." \
  "setup retries silently no-oped because internal orchestrator state was never reset" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt, runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt"

echo "==> migrating active shell defects (all RESOLVED per audit)"

make_issue fix scripts p3 historical \
  "VirGL server could die silently mid-session causing a black screen" \
  "**Problem:** virgl_test_server_android could crash or exit after the initial health check passed. Nothing monitored the process. The Unix socket stayed on disk briefly, so Mesa virpipe connected but got no response and the screen went black with zero error output. Session health monitor only checked the sentinel file, not the VirGL PID.

**Decision:** Background watchdog subshell monitors the VirGL PID, writes virgl.status on death with exit code and last 30 log lines. SessionHealthMonitor reads virgl.pid and reports VirGL death alongside session status. Always redirect VirGL stderr to ~/virgl-server.log.

**Tradeoff:** One extra background subshell per launch. Accepted because silent VirGL death was otherwise invisible.

**Evidence (audit):** launch-runelite.sh:570 VIRGL_LOG; :577 stderr redirect; :608 virgl.pid; :681-685 VIRGL_WATCHDOG dumps log on death." \
  "silent VirGL death produced an indistinguishable black screen with no error trail" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix tests p3 historical \
  "nested-quoted sed stripped every 'r' instead of CRs" \
  "**Problem:** sed -i s/\\\\r// file passed through Git Bash -> adb -> run-as -> bash reduced \\\\r to just r, so sed stripped every literal r character from files. export became expot, dirname became diname.

**Decision:** For stripping CR use tr -d \"\\015\" (octal, no escaping issues), or even better push a self-contained script to /data/local/tmp and run it so there is no multi-layer quoting at all.

**Tradeoff:** /data/local/tmp flow adds one push step; accepted because nested sed quoting is a tar pit.

**Evidence (audit):** gl-tests/scripts/device-run.sh uses the self-contained-script path; no sed \\\\r invocation remains." \
  "multi-layer shell quoting turned a CR-strip into a character-wide file corruption" \
  "runelite-tablet/gl-tests/scripts/device-run.sh"

make_issue fix tests p3 historical \
  "Git Bash leaked \$PATH and \$HOME into adb shell commands" \
  "**Problem:** adb shell \"run-as com.termux bash -c 'export PATH=\$PREFIX/bin:\$PATH'\" expanded \$PATH on the Windows Git Bash side before reaching adb, even inside single quotes nested in double quotes. The resulting 2000+ char Windows PATH with spaces and parentheses broke shell syntax on the device.

**Decision:** Use self-contained scripts pushed to /data/local/tmp and invoke them with adb shell bash /data/local/tmp/script.sh. Scripts self-bootstrap Termux env. Never reference \$PATH or \$HOME in inline adb commands.

**Tradeoff:** Requires the script-push step. Accepted because inline adb quoting is not salvageable across Git Bash + Android shell.

**Evidence (audit):** gl-tests/scripts/device-run.sh documents the invocation pattern and self-bootstraps env internally." \
  "Git Bash silently mangled every inline adb command that touched PATH" \
  "runelite-tablet/gl-tests/scripts/device-run.sh"

make_issue fix tests p3 historical \
  "GL_DEPTH_CLAMP broke virglrenderer on GLES hosts" \
  "**Problem:** Mesa 4.5COMPAT auto-enables GL_DEPTH_CLAMP. GLES 3.2 host (ANGLE) has no GL_DEPTH_CLAMP and returns GL_INVALID_ENUM. The stale error caused virglrenderer to silently discard all subsequent draws and clears, making every FBO look broken (A=0 after glClear).

**Decision:** Export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp so Mesa never emits depth-clamp commands. Match GL and GLSL versions (4.3COMPAT + 430, not 4.5COMPAT + 330). Use MESA_DEBUG=1 to trace GL_INVALID_ENUM in virgl pipelines.

**Tradeoff:** Gave up depth-clamp on GLES hosts. Accepted because RuneLite does not use it.

**Evidence (audit):** gl-tests/scripts/run-tests.sh:235 exports the MESA_EXTENSION_OVERRIDE." \
  "a single GL error cascade silently invalidated every subsequent draw" \
  "runelite-tablet/gl-tests/scripts/run-tests.sh"

make_issue fix tests p3 historical \
  "GLES-unsupported glGetIntegerv tokens hard-crashed virpipe" \
  "**Problem:** GL_MAX_VARYING_FLOATS (desktop GL 2.0) is not in GLES 3.x. Querying it via virpipe crashed because the translation layer dereferenced a null mapping rather than returning GL_INVALID_ENUM. Also: glXGetProcAddressARB never returns NULL on Mesa, so stubs crashed when called.

**Decision:** Remove GLES-unsupported tokens from query tables (GL_MAX_VARYING_FLOATS, GL_MAX_CLIP_DISTANCES, GL_DEPTH_BITS, GL_STENCIL_BITS, GL_SAMPLE_BUFFERS, GL_SAMPLES, GL_SUBPIXEL_BITS). Version-gate glGetStringi (verify GL >= 3.0 and probe index 0 before loop). fflush before each query for crash bisection.

**Tradeoff:** Diagnostic output is slightly less complete on GLES hosts. Accepted because the alternative was a guaranteed SIGSEGV on probe.

**Evidence (audit):** gl_test_log.h:282-295 comments out the unsafe tokens; :341-348 version-gates glGetStringi and probes index 0 with NULL guard." \
  "a single diagnostic query silently crashed the GL probe on GLES hosts" \
  "runelite-tablet/gl-tests/src/gl_test_log.h"

echo "==> migrating active termux defects (all RESOLVED per audit)"

make_issue perf native p3 historical \
  "Xlorie EXA driver missing UploadToScreen forced 100% software fallback" \
  "**Problem:** lorieExa defined PrepareAccess, FinishAccess, and CreatePixmap2 but no UploadToScreen. exaDoPutImage bailed at !pExaScr->info->UploadToScreen and fell through to ExaCheckPutImage -> fbPutImage (pure software). Every XPutImage went through the slow CPU path even though AHARDWAREBUFFER-backed pixmaps can accept direct writes. RuneLite's GPU plugin issues ~1500 PutImages/sec in 22-row strips, 100% on the software fallback.

**Decision:** Implement lorieUploadToScreen that locks the AHB once, row-stride-aware memcpy src -> pixmap base + (y*stride + x*bpp), unlock. Wire it into lorieExa. Verify accel vs fallback with EXA_ACCEL_TRACE_EVERY counters when touching driver ops.

**Tradeoff:** Accepted: the memcpy still runs on CPU, but it writes directly to GPU-mapped memory instead of going through fb. Rejected: attempting true-zero-copy via DMA in this slice; larger change.

**Evidence (audit):** InitOutput.c:1031 defines lorieUploadToScreen; :1084 wires .UploadToScreen into the driver struct. On-device run shows EXA accel 1837 / fallback 8 over 150s; damage-triggered-redraws moved 44-51 -> 60-66 FPS." \
  "100% of RuneLite's per-frame pixel work was going through fbPutImage CPU copy" \
  "third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c, third_party/termux-x11-upstream/app/src/main/cpp/xserver/exa/exa_accel.c:163"

make_issue chore native p3 historical \
  "Xlorie ErrorF does not route to logcat" \
  "**Problem:** ErrorF in the xserver core goes through LogVWrite -> write(fd=2, ...) -> stderr. In the Xlorie cmd process stderr is not captured by logcat; only a TERMUX_X11_DEBUG=1-gated pipe thread forwards it, and even then not to a named tag. Any instrumentation using ErrorF (EXA_ACCEL_TRACE, pre-session-72 DamageTraceV2) silently vanished.

**Decision:** For new native instrumentation under xserver/ or miext/, use __android_log_print(ANDROID_LOG_INFO, \"LorieNative\", ...) which matches the rest of Lorie's logging. Before trusting any ErrorF-based probe, verify at least one line reaches logcat by exercising a known-hit path.

**Tradeoff:** Accepted: instrumentation lives outside the upstream X server logging abstraction. The alternative was hours of missing-log confusion.

**Evidence (audit):** damage.c:47 defines DAMAGE_TRACE_LOG via __android_log_print; used at :188 and :218 for DamageTraceV2." \
  "hours of a session were lost to instrumentation that printed to /dev/null" \
  "third_party/termux-x11-upstream/app/src/main/cpp/xserver/miext/damage/damage.c, third_party/termux-x11-upstream/app/src/main/cpp/xserver/exa/exa_accel.c:41"

make_issue fix setup p3 historical \
  "broadcast-created ScriptManager re-deployed and hit Android 14 BAL" \
  "**Problem:** HybridX11TestReceiver constructed a fresh ScriptManager(context, runner) per broadcast, with a per-instance scriptsDeployed flag that started false. The activity setup flow had already deployed scripts, but the fresh instance did not see that, so it re-deployed. Termux's RunCommandService processed the deploy, then tried to call TermuxResultService on the app side; Android 14+ denied the cross-app service start as \"Background started FGS: Disallowed\" because the broadcast receiver is not a TOP-state caller. Every launcher broadcast hit the 30s timeout and ANR'd.

**Decision:** Promote scriptsDeployed and configsDeployed to @Volatile companion-object fields so every ScriptManager instance in the process reads the same state.

**Tradeoff:** Accepted a process-global flag (not per-instance). Rejected: sharing a full ScriptManager instance across activity + receiver; too invasive for the DI container.

**Evidence (audit):** ScriptManager.kt:17-33 companion object holds scriptsDeployedGlobal / configsDeployedGlobal; instance properties at :36-42 delegate to the companion fields." \
  "every launcher broadcast was ANRing because of a redundant re-deploy on Android 14" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/ScriptManager.kt, runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11TestReceiver.kt:241"

echo "==> migrating archive defects (all RESOLVED or STALE per audit; STALE are skipped)"

make_issue fix scripts p3 historical \
  "MESA_GLSL_VERSION_OVERRIDE was missing alongside MESA_GL_VERSION_OVERRIDE" \
  "**Problem:** MESA_GL_VERSION_OVERRIDE=4.1COMPAT overrode the GL version string but not the GLSL version. RuneLite GPU plugin requires GLSL 3.30 but VirGL stock Mesa reports GLSL 1.50 max, so the plugin crashed with \"GLSL 3.30 is not supported\".

**Decision:** Always set MESA_GL_VERSION_OVERRIDE and MESA_GLSL_VERSION_OVERRIDE together.

**Tradeoff:** None meaningful.

**Evidence (audit):** launch-runelite.sh:796-797 sets both overrides." \
  "GPU plugin crashed on boot because GLSL version was not overridden" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix scripts p3 historical \
  "lfdevs Mesa broke virpipe on Mali with 32-bit BadMatch" \
  "**Problem:** lfdevs Mesa (mesa-for-android-container) is built for Adreno/Turnip. On Mali, virpipe selected a 32-bit RGBA visual but Termux:X11's root window is 24-bit RGB, so XGetSubImage failed with BadMatch and glxinfo crashed before printing GL strings.

**Decision:** Use stock Ubuntu Mesa for Mali/VirGL. Set MESA_GLX_ALPHA_BITS=0 to force a 24-bit visual. Use glxgears (XPutImage) rather than glxinfo (XGetImage) for virpipe detection.

**Tradeoff:** Give up lfdevs' Turnip niceties on Mali. Accepted: Turnip does not help us on Mali anyway.

**Evidence (audit):** setup-gpu-mali.sh:21-42 uses stock Ubuntu Mesa and removes lfdevs libgallium; launch-runelite.sh:776 exports MESA_GLX_ALPHA_BITS=0." \
  "lfdevs Mesa on Mali produced an unrenderable visual mismatch" \
  "runelite-tablet/app/src/main/assets/scripts/setup-gpu-mali.sh, runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix tests p3 historical \
  "LD_LIBRARY_PATH=\$PREFIX/lib crashed virgl_test_server_android" \
  "**Problem:** Self-bootstrap export LD_LIBRARY_PATH=\$PREFIX/lib caused virgl_test_server_android to find Termux's OpenSSL 3.x (which removed OpenSSL_add_all_algorithms) instead of the system OpenSSL that libsqlite.so needs. ANGLE dlopen SIGSEGV'd.

**Decision:** Start VirGL server with env -u LD_LIBRARY_PATH virgl_test_server_android. Termux binaries have the correct rpath baked in.

**Tradeoff:** None: unsetting LD_LIBRARY_PATH is the right thing.

**Evidence (audit):** gl-tests/scripts/run-tests.sh:157 uses env -u LD_LIBRARY_PATH." \
  "the VirGL server was segfaulting because LD_LIBRARY_PATH dragged in wrong OpenSSL" \
  "runelite-tablet/gl-tests/scripts/run-tests.sh"

make_issue fix scripts p3 historical \
  "X11 cleanup pkill did not match com.termux.x11.Loader" \
  "**Problem:** pkill -f 'termux-x11' did not match the X server binary, which actually runs as app_process ... com.termux.x11.Loader :0. Stale X11 servers blocked new sessions with \"server already running\".

**Decision:** Kill both patterns in every cleanup location: pkill -f 'termux-x11' AND pkill -f 'com.termux.x11.Loader'.

**Tradeoff:** None.

**Evidence (audit):** launch-runelite.sh:222, 278, 351 and shutdown-session.sh:62, 110 match com.termux.x11.Loader." \
  "stale X servers kept blocking launches because pkill missed the real process name" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh, runelite-tablet/app/src/main/assets/scripts/shutdown-session.sh"

make_issue fix scripts p3 historical \
  "xrandr --newmode did not work on Termux:X11" \
  "**Problem:** Termux:X11's X server does not implement rrCrtcTransformSet, so xrandr --newmode and --scale are silently ignored.

**Decision:** Use termux-x11-preference displayResolutionMode:custom displayResolutionCustom:WxH. Syntax is key:value (colon), not key=value.

**Tradeoff:** Locked into Termux:X11's preference API. Accepted because xrandr just does not work here.

**Evidence (audit):** launch-runelite.sh:653-671 uses the colon syntax." \
  "xrandr-based resolution tweaks silently did nothing on Termux:X11" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue chore scripts p2 needs-repro \
  "MESA_GL_VERSION_OVERRIDE alone cannot unlock LWJGL OpenGL45" \
  "**Problem:** Setting MESA_GL_VERSION_OVERRIDE=4.5COMPAT only changes glGetString(GL_VERSION); it does not populate GL 4.5 function pointers. LWJGL's OpenGL45 boolean check_GL45() requires every GL 4.5 fn pointer non-null, so it returns false regardless of the version string.

**Decision (proposed):** Use an LD_PRELOAD shim to inject glClipControl directly, bypassing LWJGL's check.

**Tradeoff:** LD_PRELOAD is invasive and may need re-signing. Rejecting it means accepting no GL 4.5 on LWJGL.

**Evidence (audit):** No LD_PRELOAD shim is present in launch-runelite.sh today. Prevention was never applied; whether it is still required depends on whether GPU plugin still targets GL 4.5 in 2026-Q2 builds. Marked needs-repro pending runtime check." \
  "the original prevention was to use an LD_PRELOAD glClipControl shim and that shim does not exist" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh" \
  "confirm whether RuneLite GPU plugin still requires LWJGL OpenGL45 against current Mesa; if yes, ship the LD_PRELOAD shim"

make_issue fix scripts p3 historical \
  "EXIT-trap cleanup deleted the env file that the next launch needed" \
  "**Problem:** cleanup_on_exit() in launch-runelite.sh and shutdown-session.sh both deleted \$HOME/.rlt-launch-env.sh. When a new launch killed the old RuneLite, the old script's EXIT trap fired and deleted the new env file -> new script reported \"No credentials env file provided.\"

**Decision:** Source the env file at the very top of launch-runelite.sh (before cleanup_previous). Remove the file deletion from cleanup_on_exit and shutdown-session.sh.

**Tradeoff:** Env file lingers on disk until the next launch overwrites it. Accepted: credentials are also deleted from disk at the point the script finishes using them (rm -f creds).

**Evidence (audit):** launch-runelite.sh:234-295 sources creds early; :359 cleanup_on_exit no longer deletes .rlt-launch-env.sh." \
  "back-to-back launches raced on a shared env file and lost credentials" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh, runelite-tablet/app/src/main/assets/scripts/shutdown-session.sh"

make_issue fix auth p3 historical \
  "auth refresh did only Step 1 and 3, missing game-session recreation" \
  "**Problem:** refreshTokens() renewed Step 1 launcher tokens but skipped Step 2 (consent) and Step 3 (createGameSession). JX_SESSION_ID expires server-side (~12 days). Refresh \"succeeded\" but game login silently failed because the session was stale.

**Decision:** Pre-launch validateSession() checks the session via GET /accounts with Bearer sessionId. On 401/403 (Expired) auto-trigger GeckoView re-auth with pendingLaunchAfterAuth resume. On NetworkError log and continue.

**Tradeoff:** One extra HTTP round-trip on every launch. Accepted because silent session-expiry was making launches look like OAuth failures.

**Evidence (audit):** JagexOAuth2Manager.kt:88 validateSession() exists and hits /accounts, handling 401/403." \
  "token refresh \"succeeded\" while the game session was already dead" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt, runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt"

make_issue fix auth p3 historical \
  "GeckoAuthActivity must handle process death" \
  "**Problem:** GeckoView sessions and lateinit vars cannot survive process death. A restored Activity crashed with UninitializedPropertyAccessException.

**Decision:** Check savedInstanceState != null in onCreate and finish with an error. Also add FLAG_SECURE so the login page does not appear in recents.

**Tradeoff:** Restored instances are unrecoverable - user must re-auth from scratch. Accepted because GeckoView state is not serializable.

**Evidence (audit):** GeckoAuthActivity.kt:33 sets FLAG_SECURE; :37 checks savedInstanceState != null." \
  "process-death restore was crashing the auth flow on lateinit access" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt"

make_issue fix auth p3 historical \
  "EncryptedSharedPreferences corruption bricked credential storage" \
  "**Problem:** Power loss or disk corruption broke the encrypted prefs XML file. The next create() call threw GeneralSecurityException and returned null forever; the user had to uninstall.

**Decision:** On GeneralSecurityException, delete the corrupted file and retry once. Guard with a prefsRecreateAttempted flag to prevent infinite loops.

**Tradeoff:** Deleting the file loses any credentials in it. Accepted because the file is unreadable anyway.

**Evidence (audit):** CredentialManager.kt:35 prefsRecreateAttempted; :63 and :82-86 recreate-on-exception." \
  "corrupted encrypted prefs left the app unable to load credentials, ever" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt"

make_issue fix setup p3 historical \
  "app-private filesDir was not readable by Termux" \
  "**Problem:** Writing a file to context.filesDir (/data/user/0/com.runelitetablet/files/) and passing the path to Termux failed silently because Termux runs as a different UID and cannot read it. [ -f \"\$path\" ] returned false.

**Decision:** Deploy files to Termux via TermuxCommandRunner.execute() with stdin (same pattern as script deployment). The file lands in Termux's home dir where it is accessible.

**Tradeoff:** Slight IPC overhead per deploy. Accepted because the alternative does not work at all.

**Evidence (audit):** launch-runelite.sh:503-505 uses \$PREFIX/tmp/.rlt-creds-\$\$.sh; creds are sourced from the Termux path." \
  "passing filesDir paths to Termux was silently failing every launch" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt"

make_issue fix scripts p3 historical \
  "Termux processes survived Android app force-stop" \
  "**Problem:** am force-stop com.runelitetablet killed only our app. Termux is a separate process, so java/proot/openbox/PulseAudio/X11 kept running as zombies.

**Decision:** Run cleanup_previous() at the top of the launch script that pkills every known process pattern. Add a comprehensive EXIT trap for clean shutdown.

**Tradeoff:** A few extra milliseconds on launch. Accepted.

**Evidence (audit):** launch-runelite.sh:255 cleanup_previous(); :322 cleanup_on_exit EXIT trap." \
  "leftover Termux processes kept occupying sockets/X displays after a force-stop" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix auth p3 historical \
  "OkHttp .execute() blocked coroutine cancellation" \
  "**Problem:** httpClient.newCall(request).execute() is a blocking call that does not respond to coroutine cancellation. Screen changes while an auth call was in flight leaked the thread.

**Decision:** Use suspendCancellableCoroutine + call.enqueue() + invokeOnCancellation { call.cancel() }.

**Tradeoff:** Slightly more code per call. Accepted.

**Evidence (audit):** JagexOAuth2Manager.kt:141-142 executeCancellable uses the suspendCancellable pattern." \
  "cancelling the calling coroutine did not actually cancel OkHttp in flight" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt"

make_issue docs ui p3 historical \
  "Android clipboard corrupts single quotes when pasting into Termux" \
  "**Problem:** Android or keyboard substitutes curly/smart quotes for straight quotes, breaking shell syntax when a user copies an instruction from the app UI.

**Decision:** Avoid single quotes in commands users must paste. Use no-quote alternatives or double quotes.

**Tradeoff:** Slightly more awkward example commands. Accepted.

**Evidence:** Documentation-level guidance; no code signature to verify." \
  "paste-based setup instructions were silently mangled by smart quotes" \
  ""

make_issue refactor ui p3 historical \
  "@Volatile var is not reactive in StateFlow derivations" \
  "**Problem:** A @Volatile var read inside Flow.map{} or combine{} does not trigger re-evaluation when the field changes.

**Decision:** Use MutableStateFlow<Boolean> instead. Combine with combine() for reactive derivations.

**Tradeoff:** StateFlow is heavier than @Volatile. Accepted: correctness beats micro-footprint.

**Evidence (audit):** 42 MutableStateFlow occurrences across 15 files; advisory rule present in .claude/rules/coroutine-safety.md." \
  "reactive UI state silently stopped updating whenever anyone reached for @Volatile" \
  ".claude/rules/coroutine-safety.md"

make_issue fix auth p3 historical \
  "Android WebView is incompatible with Cloudflare" \
  "**Problem:** Cloudflare's multi-layer detection permanently blocks WebView-based browsing. Every Jagex auth attempt from inside a WebView was blocked.

**Decision:** Use GeckoView (Firefox engine) for Cloudflare-protected pages.

**Tradeoff:** GeckoView adds a large native dependency. Accepted because there is no alternative for Cloudflare-protected flows on Android.

**Evidence (audit):** GeckoAuthActivity.kt and GeckoAuthRuntime.kt exist; WebView path replaced." \
  "Cloudflare blocked every WebView-based auth attempt, leaving no usable login path" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt"

make_issue fix setup p3 historical \
  "env vars injected via cmd string prefix did not reach the Termux script" \
  "**Problem:** Prepending export VAR=val; bash script.sh to the Termux RUN_COMMAND commandPath string did not work because Termux passes commandPath as the literal executable path to execve(), not to a shell. Credentials never reached the script.

**Decision:** Pass credentials via a temp file in Termux-side storage. The script sources it and immediately rm -f s. Never pass secrets as command-line arguments (also visible in ps).

**Tradeoff:** Extra file-write on every launch. Accepted because command-prefix injection simply does not work.

**Evidence (audit):** launch-runelite.sh:503-505 uses \$PREFIX/tmp/.rlt-creds-\$\$.sh sourced and deleted by the script." \
  "every attempt at command-prefix env injection was silently dropped by execve" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt, runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix setup p3 historical \
  "startActivity from background context is blocked on Android 10+" \
  "**Problem:** Calling context.startActivity() from SetupOrchestrator (which holds applicationContext) to bring Termux:X11 to the foreground was silently dropped on Android 10+ when the app was not in the foreground. No exception was thrown.

**Decision:** Route every Activity start through SetupActions.launchIntent() callback (the Activity is in the foreground when the user taps Launch).

**Tradeoff:** The orchestrator needs a callback for this. Accepted: background starts are not coming back.

**Evidence (audit):** SetupOrchestrator.kt:34 and :111 route through actions.launchIntent()." \
  "silent-dropped startActivity from the background looked like a broken Launch button" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt"

make_issue fix ui p3 historical \
  "RuneLite window opened tiny because no window manager was running" \
  "**Problem:** RuneLite rendered at 1038x503 on a 2960x1711 X11 desktop. A bare X11 server with no window manager lets windows open at their default size (OSRS default is 765x503 + sidebar).

**Decision:** Install openbox in the proot rootfs and configure it to auto-maximize with no decorations.

**Tradeoff:** Adds an openbox dependency. Accepted: the alternative is a tiny unusable window.

**Evidence (audit):** openbox references at launch-runelite.sh:266, :336; preflight at :752." \
  "users saw a postage-stamp-sized game because there was no WM to size it" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix scripts p3 historical \
  "openjdk-11-jdk-headless was missing libawt_xawt" \
  "**Problem:** RuneLite launcher crashed with UnsatisfiedLinkError: libawt_xawt.so. The headless JDK excludes AWT, Swing, and X11 libraries.

**Decision:** Install openjdk-11-jdk (full JDK) instead of openjdk-11-jdk-headless.

**Tradeoff:** Larger rootfs. Accepted because headless JDK cannot run a windowed client.

**Evidence (audit):** setup-environment.sh:149 installs openjdk-11-jdk." \
  "the wrong JDK flavor was making the launcher unable to load AWT" \
  "runelite-tablet/app/src/main/assets/scripts/setup-environment.sh"

make_issue fix scripts p3 historical \
  "X11 socket was invisible inside proot because /tmp was not bind-mounted" \
  "**Problem:** AWTError: Can't connect to X11 window server using ':0'. Proot's /tmp is isolated from Termux's \$PREFIX/tmp, so the X11 socket created by Termux:X11 was invisible to the JVM running inside proot.

**Decision:** Add --bind \"\$PREFIX/tmp/.X11-unix:/tmp/.X11-unix\" to proot-distro login (also covered by --shared-tmp).

**Tradeoff:** Slight bind-mount overhead. Accepted.

**Evidence (audit):** launch-runelite.sh:716 and :1184 use --shared-tmp; X11_SOCKET_DIR at :27." \
  "proot isolation was hiding the X11 socket from RuneLite" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix scripts p3 historical \
  "JvmLauncher child died because proot's --kill-on-exit killed the child with the parent" \
  "**Problem:** The RuneLite launcher spawned the client via ProcessBuilder, but proot's --kill-on-exit killed the child when the launcher exited.

**Decision:** Run net.runelite.client.RuneLite directly via exec java -cp ... bypassing the launcher's child-spawn path.

**Tradeoff:** We lose the launcher's auto-update for the client's runtime behavior. That was later addressed by the repository2/ cache-clear defect.

**Evidence (audit):** launch-runelite.sh:1023, :1050 exec java -cp ... net.runelite.client.RuneLite." \
  "the client was being killed the moment the launcher finished" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix scripts p3 historical \
  "proot-distro install cleaned up rootfs on non-zero exit" \
  "**Problem:** proot-distro install ubuntu returned non-zero due to harmless /proc/self/fd/1,2 binding warnings, which triggered its own rootfs-cleanup and left no usable install.

**Decision:** Manual tar-based rootfs extraction with post-install DNS/hosts/env writes, used as a fallback.

**Tradeoff:** Duplicates work proot-distro would have done. Accepted because proot-distro's own path was unusable.

**Evidence (audit):** setup-environment.sh:77 manual rootfs extraction fallback; :100-106 writes resolv.conf/hosts after extraction." \
  "proot-distro was auto-destroying the rootfs we had just extracted" \
  "runelite-tablet/app/src/main/assets/scripts/setup-environment.sh"

make_issue chore setup p2 needs-repro \
  "SetupOrchestrator isSuccess was too strict for proot exit codes" \
  "**Problem:** isSuccess checked exitCode == 0 && error == null. Proot commands commonly returned non-zero due to harmless /proc/self/fd binding warnings, causing setup scripts to be treated as failed even after completing every step.

**Decision (historical):** Added a success-marker check that looked for \"=== Setup complete ===\" in stdout as an alternative to exitCode == 0.

**Tradeoff:** Two success signals to keep in sync (exit code and marker string).

**Evidence (audit):** No marker-string match found in the current SetupOrchestrator.kt via grep. The success-marker prevention may have been refactored into LaunchCoordinator or the shell scripts, or it may have been dropped. Needs runtime verification on a real proot-dependent setup step." \
  "the success-marker prevention is not visibly present in the current orchestrator, status unclear" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt" \
  "find the current isSuccess path for proot commands and confirm there is a working non-exitcode signal"

make_issue fix termux p3 historical \
  "TermuxResultService onDestroy cleared static pendingResults" \
  "**Problem:** onDestroy() cancelled all deferreds in the static pendingResults map. Static fields outlive service instances, so cancellation was cancelling future work.

**Decision:** Remove deferred cancellation from onDestroy(). Add stopSelfIfIdle() that only stops the service when there are no pending results.

**Tradeoff:** Service may linger slightly longer. Accepted because mid-flight result cancellation was worse.

**Evidence (audit):** TermuxResultService.kt:147 comment explicitly says \"not clearing - deferreds are static\"; :124 stopSelfIfIdle present." \
  "service teardown was nuking deferreds that the next service instance still needed" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/termux/TermuxResultService.kt"

make_issue fix scripts p3 historical \
  "manual rootfs extraction skipped post-install config" \
  "**Problem:** Manual tar extraction of the Ubuntu rootfs skipped resolv.conf, hosts, and environment writes that proot-distro install would have done. apt-get then hung on DNS.

**Decision:** Script writes resolv.conf, hosts, and environment immediately after manual extraction.

**Tradeoff:** Duplicates a bit of proot-distro's logic. Accepted.

**Evidence (audit):** setup-environment.sh:100-106 writes resolv.conf and hosts after extraction." \
  "apt-get was hanging on a DNS-less rootfs after manual extraction" \
  "runelite-tablet/app/src/main/assets/scripts/setup-environment.sh"

make_issue fix scripts p3 historical \
  "DEBIAN_FRONTEND=noninteractive was required for apt-get in no-PTY mode" \
  "**Problem:** debconf prompts hung even with -y when stdin was /dev/null, which is how proot-distro login -c runs apt-get.

**Decision:** Add env DEBIAN_FRONTEND=noninteractive before the bash -c in proot-distro login calls.

**Tradeoff:** None.

**Evidence (audit):** setup-environment.sh:132, :140 prefix the apt-get calls." \
  "apt-get was hanging on debconf prompts that no human could answer" \
  "runelite-tablet/app/src/main/assets/scripts/setup-environment.sh"

make_issue fix scripts p3 historical \
  "Termux X11 socket lives at \$PREFIX/tmp, not /tmp" \
  "**Problem:** Code paths that assumed /tmp/.X11-unix/X0 failed because Termux:X11 actually creates the socket at \$PREFIX/tmp/.X11-unix/X0.

**Decision:** Use \$PREFIX/tmp/.X11-unix in every socket-path reference.

**Tradeoff:** None.

**Evidence (audit):** launch-runelite.sh:27 X11_SOCKET_DIR=\"\$PREFIX/tmp/.X11-unix\"." \
  "X connect was failing because the socket path assumption was wrong" \
  "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix ui p3 historical \
  "user had to manually switch apps after tapping Launch" \
  "**Problem:** After the user tapped Launch in the app, nothing brought Termux:X11 to the foreground. User had to switch apps manually, and the context switch was unintuitive.

**Decision:** Kotlin sends a CHANGE_PREFERENCE broadcast (fullscreen, no keyboard bar). The shell script polls the X11 socket, then runs am start to bring Termux:X11 to the foreground.

**Tradeoff:** Depends on the Termux:X11 broadcast contract. Accepted.

**Evidence (audit):** launch-runelite.sh:478 am start for X11 host; :488 references CHANGE_PREFERENCE broadcast." \
  "a broken Launch UX made users think the app had stopped responding" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt, runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh"

make_issue fix auth p3 historical \
  "Android port 80 is unusable, auth must intercept via GeckoView" \
  "**Problem:** Android blocks bind() to privileged ports (<1024). VpnService cannot intercept loopback. Intent filters for http://localhost are dead on Android 12+. proot does not translate bind() for privileged ports. The OAuth callback could not be caught on :80 by any approach.

**Decision:** Use GeckoView NavigationDelegate.onLoadRequest() to intercept the redirect at engine level before network. No port 80 needed.

**Tradeoff:** GeckoView is a large dependency. Accepted because no alternative works on Android.

**Evidence (audit):** GeckoAuthActivity.kt is present and implements the NavigationDelegate intercept; port 80 path is gone." \
  "every attempt to catch the auth redirect on localhost:80 was blocked by Android" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt"

# Two stale auth defects are SKIPPED (AuthRedirectCapture.kt deleted).

make_issue refactor auth p3 historical \
  "Kotlin data class toString() leaks sensitive fields" \
  "**Problem:** Auto-generated toString() includes every data-class field. Any accidental log line exposes plaintext secrets.

**Decision:** Always override toString() to return a constant \"ClassName([REDACTED])\" for classes that hold credentials.

**Tradeoff:** toString() stops being useful for debugging those classes. Accepted: safety beats convenience.

**Evidence (audit):** OAuthTypes.kt:15 and CredentialStore.kt:30 both override toString with REDACTED placeholder." \
  "one stray log line could have leaked Jagex tokens to anyone with logcat" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt, runelite-tablet/app/src/main/java/com/runelitetablet/auth/OAuthTypes.kt"

make_issue fix auth p3 historical \
  "game-session API used the wrong auth method" \
  "**Problem:** fetchCharacters() and createGameSession() passed accessToken as a Bearer header. The real flow is: POST {\"idToken\":\"<jwt>\"} to /sessions to get a sessionId, then GET /accounts with Bearer <sessionId>. The endpoint order was also reversed.

**Decision:** Call /sessions first with id_token in the JSON body, then /accounts with the returned sessionId as the Bearer.

**Tradeoff:** None; this matches the real Jagex contract.

**Evidence (audit):** JagexOAuth2Manager.kt:72 createGameSession(idToken) POSTs to SESSIONS_ENDPOINT; order correct." \
  "we were calling Jagex endpoints with the wrong tokens and a reversed order" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt"

make_issue fix auth p3 historical \
  "consent client_id cannot initiate Step 1 login" \
  "**Problem:** Using 1fddee4e-... (the Step 2 consent client_id) for the initial OAuth login with response_type=code or id_token code caused Jagex to return unsupported_response_type. The consent client only works for Step 2 after a Step 1 session exists.

**Decision:** Always use com_jagex_auth_desktop_launcher for Step 1. The consent client is Step 2 only.

**Tradeoff:** Two client IDs in config. Accepted because it matches Jagex's contract.

**Evidence (audit):** JagexOAuth2Manager.kt:42 and OAuthUrls.kt:15 use com_jagex_auth_desktop_launcher for Step 1." \
  "initial OAuth login was blocked by Jagex with unsupported_response_type" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt, runelite-tablet/app/src/main/java/com/runelitetablet/auth/OAuthUrls.kt"

make_issue fix auth p3 historical \
  "Jagex rejected Step 1 requests because we used the wrong client_id" \
  "**Problem:** JagexOAuth2Manager.kt used 1fddee4e-... (Step 2 consent client) for Step 1, and Jagex rejected the request with \"something went wrong\".

**Decision:** Implement the correct 2-step flow with two client IDs (launcher client for Step 1, consent client for Step 2).

**Tradeoff:** Same as the other Step-1 client_id issue.

**Evidence (audit):** See related issue on consent client_id; fix is unified." \
  "Jagex was silently rejecting our auth before it ever reached consent" \
  "runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt"

make_issue fix scripts p3 historical \
  "CRLF line endings broke shebang on Termux" \
  "**Problem:** Windows git auto-converted LF to CRLF on checkout. Shell scripts deployed to Termux via cat > file retained \\r in the shebang line, and the kernel returned ENOENT on exec.

**Decision:** .gitattributes with *.sh text eol=lf to force LF on checkout. Defensive replace(\"\\\\r\", \"\") in ScriptManager before writing scripts to the device.

**Tradeoff:** None.

**Evidence (audit):** .gitattributes:2 *.sh text eol=lf; ScriptManager.kt:92 .replace(\"\\\\r\", \"\")." \
  "every script deploy from Windows was bricked by invisible carriage returns" \
  ".gitattributes, runelite-tablet/app/src/main/java/com/runelitetablet/setup/ScriptManager.kt"

echo ""
echo "==> migration complete: created=$created skipped=$skipped failed=$failed"

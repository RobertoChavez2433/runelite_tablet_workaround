# Defects Archive

Older/resolved defects rotated from per-feature files in `.claude/defects/`.

---

## Shell (rotated 2026-03-10, Session 51)

### [SHELL] 2026-03-09: X11 cleanup misses com.termux.x11.Loader process
**Pattern**: `pkill -f 'termux-x11'` doesn't match the actual X server binary which runs as `app_process ... com.termux.x11.Loader :0`. Stale X11 server blocks new sessions with "server already running".
**Prevention**: Kill both patterns: `pkill -f 'termux-x11'` AND `pkill -f 'com.termux.x11.Loader'` in all cleanup locations.

### [SHELL] 2026-03-09: xrandr --newmode/--scale doesn't work with Termux:X11
**Pattern**: Termux:X11's X server doesn't implement `rrCrtcTransformSet`. `xrandr --newmode` modes are ignored.
**Prevention**: Use `termux-x11-preference displayResolutionMode:custom displayResolutionCustom:WxH`. Syntax is `key:value` (colon), NOT `key=value`.

## Shell (rotated 2026-03-10)

### [SHELL] 2026-03-09: MESA_GL_VERSION_OVERRIDE doesn't unlock LWJGL's OpenGL45 — function pointers NULL
**Pattern**: Setting `MESA_GL_VERSION_OVERRIDE=4.5COMPAT` changes `glGetString(GL_VERSION)` to "4.5" but does NOT make all GL 4.5 function pointers available. LWJGL's `OpenGL45` boolean checks ALL GL 4.5 function pointers via `check_GL45()` — if any are NULL, `OpenGL45=false` regardless of version string.
**Prevention**: Don't rely on Mesa version overrides for feature availability. Use LD_PRELOAD shim to inject `glClipControl` call directly.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

## Rotated 2026-03-09 (Session 49)

### [SHELL] 2026-03-09: Old launch script EXIT trap deletes env file before new script reads it — KNOWN
**Pattern**: `cleanup_on_exit()` EXIT trap in launch-runelite.sh and `shutdown-session.sh` both delete `$HOME/.rlt-launch-env.sh`. When new launch kills old RuneLite, old script's EXIT trap fires → deletes newly deployed env file → new script says "No credentials env file provided."
**Prevention**: Source env file BEFORE `cleanup_previous()` at top of launch-runelite.sh (file is read and deleted before old script's EXIT trap can race). Remove env file deletion from `cleanup_on_exit()` and `shutdown-session.sh`.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh, @runelite-tablet/app/src/main/assets/scripts/shutdown-session.sh

---

## Rotated 2026-03-09 (Session 47)

### [ANDROID] 2026-03-08: Auth refresh only does Step 1/3 — game session not recreated — FIXED Session 45
**Pattern**: `refreshTokens()` renews Step 1 launcher tokens but skips Step 2 (consent browser flow) and Step 3 (createGameSession). `JX_SESSION_ID` expires server-side (~12 days). Refresh "succeeds" but game login silently fails because session is stale.
**Prevention**: Pre-launch `validateSession()` checks session via GET `/accounts` with Bearer sessionId. On 401/403 (Expired), auto-triggers GeckoView re-auth with `pendingLaunchAfterAuth` resume. On NetworkError, logs and continues.
**Status**: FIXED Session 45.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt (validateSession), @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (performLaunch)

### [ANDROID] 2026-02-24: GeckoAuthActivity must handle process death — check savedInstanceState
**Pattern**: GeckoView sessions and `lateinit` vars cannot survive process death. Restored Activity crashes with `UninitializedPropertyAccessException`.
**Prevention**: Check `savedInstanceState != null` in `onCreate` and finish with error. Also add `FLAG_SECURE` to prevent login page appearing in recents.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt

### [ANDROID] 2026-02-24: EncryptedSharedPreferences corruption bricks credential storage permanently
**Pattern**: Power loss or disk corruption can break the encrypted prefs XML file. Next `create()` call throws `GeneralSecurityException` and returns null forever — user must uninstall app.
**Prevention**: On exception, delete the corrupted file and retry once. Guard with a `prefsRecreateAttempted` flag to prevent infinite loops.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt

---

## March 2026

### [ANDROID] 2026-02-24: Cross-app file access — app's private filesDir is not readable by Termux
**Pattern**: Writing a file to `context.filesDir` (`/data/user/0/com.runelitetablet/files/`) and passing the path to Termux. Termux runs as a different UID and cannot read it — `[ -f "$path" ]` silently fails.
**Prevention**: Deploy files to Termux via `TermuxCommandRunner.execute()` with stdin (same pattern as script deployment). File lands in Termux's home dir where it's accessible.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (performLaunch)

## Rotated 2026-02-24 (Session 39)

### [SHELL] 2026-02-24: Termux processes survive Android app force-stop — must explicitly kill
**Pattern**: `am force-stop com.runelitetablet` only kills our app. Termux is a separate process — Java/proot/openbox/PulseAudio/X11 keep running as zombies.
**Prevention**: Run `cleanup_previous()` at launch script start that pkills all known process patterns. Add comprehensive EXIT trap for clean shutdown.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [SECURITY] 2026-02-24: OkHttp .execute() blocks IO thread on coroutine cancellation — use executeCancellable
**Pattern**: `httpClient.newCall(request).execute()` is a blocking call that does not respond to coroutine cancellation.
**Prevention**: Use `suspendCancellableCoroutine` + `call.enqueue()` + `invokeOnCancellation { call.cancel() }`.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

## Rotated 2026-02-24 (Session 37)

### [ANDROID] 2026-02-23: Android clipboard corrupts single quotes when pasting into Termux
**Pattern**: Android or keyboard substitutes curly/smart quotes for straight quotes, breaking shell syntax.
**Prevention**: Avoid single quotes in commands users must paste. Use no-quote alternatives or double quotes.

### [ANDROID] 2026-02-23: `@Volatile var` is not reactive — StateFlow derivations won't re-evaluate
**Pattern**: `@Volatile var` read inside `Flow.map{}` or `combine{}` won't trigger re-evaluation when changed.
**Prevention**: Use `MutableStateFlow<Boolean>` instead. Combine with `combine()` for reactive derivations.

### [ANDROID] 2026-02-23: Android WebView is fundamentally incompatible with Cloudflare — use GeckoView
**Pattern**: Cloudflare multi-layer detection blocks WebView permanently.
**Prevention**: Use GeckoView (Firefox engine) for Cloudflare-protected pages.

## Rotated 2026-02-23 (Session 34)

### [SECURITY] 2026-02-23: Localhost forwarder HTML needs CSRF token — any local process can POST
**Pattern**: Without a per-request CSRF token, any app on the device that knows the port can POST to `/jws` and inject a token.
**Prevention**: Generate a random CSRF token, embed in forwarder HTML, validate in POST handler before accepting params.
**Status**: OBSOLETE — AuthRedirectCapture.kt deleted in Session 34. GeckoView replaces localhost forwarder entirely.
**Ref**: (deleted) auth/AuthRedirectCapture.kt

### [ANDROID] 2026-02-23: Android port 80 — ALL approaches exhaustively dead except GeckoView
**Pattern**: Android blocks bind() to privileged ports (<1024) for ALL apps. VpnService can't intercept loopback. Intent filters for `http://localhost` dead on Android 12+. proot does NOT translate bind() for privileged ports.
**Prevention**: Use GeckoView `NavigationDelegate.onLoadRequest()` to intercept redirect at engine level before network. No port 80 needed.
**Status**: RESOLVED — GeckoView auth implemented in Session 34. Port 80 not needed.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/GeckoAuthActivity.kt

---

## Rotated 2026-02-23 (Session 26)

### [TERMUX] 2026-02-22: Env var injection via command string prefix doesn't work with Termux execve
**Pattern**: Prepending `export VAR=val; bash script.sh` to the Termux RUN_COMMAND `commandPath` string doesn't work — Termux passes `commandPath` as the literal executable path to `execve()`, not to a shell. Credentials never reach the script.
**Prevention**: Pass credentials via a temp file in app-private storage. Script sources and immediately `rm -f`s the file. Never pass secrets as command-line arguments (also visible in `ps`).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

---

## Rotated 2026-02-23 (Session 25)

### [ANDROID] 2026-02-22: startActivity() from background context blocked on Android 10+
**Pattern**: Calling `context.startActivity()` from `SetupOrchestrator` (which holds applicationContext) to bring Termux:X11 to the foreground. On Android 10+ this is silently dropped if the app is not in the foreground — no exception thrown.
**Prevention**: Route all Activity starts through `SetupActions.launchIntent()` callback (goes through the Activity, which IS in the foreground when the user taps Launch).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

---

### [UX] 2026-02-22: RuneLite window is tiny — not filling tablet screen — DESIGNED
**Pattern**: RuneLite renders at 1038x503 on a 2960x1711 X11 desktop. No window manager = windows open at default size.
**Root cause**: Bare X11 with no window manager. OSRS defaults to 765x503 + sidebar.
**Fix**: Install openbox WM in proot, configure auto-maximize + no decorations.
**Status**: DESIGNED — ready to implement.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

---

### [SHELL] 2026-02-22: openjdk-11-jdk-headless missing AWT/X11 libs — FIXED
**Pattern**: RuneLite launcher crashes with `UnsatisfiedLinkError: libawt_xawt.so`. Headless JDK excludes AWT/Swing/X11.
**Fix**: Changed `openjdk-11-jdk-headless` to `openjdk-11-jdk` in setup-environment.sh.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [SHELL] 2026-02-22: X11 socket not visible inside proot — bind-mount missing — FIXED
**Pattern**: `AWTError: Can't connect to X11 window server using ':0'`. Proot's `/tmp` is isolated from Termux's `$PREFIX/tmp`.
**Fix**: Added `--bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"` to `proot-distro login` in launch-runelite.sh.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [SHELL] 2026-02-22: RuneLite launcher JvmLauncher child process dies in proot — FIXED
**Pattern**: JvmLauncher spawns client via ProcessBuilder, but proot's `--kill-on-exit` kills the child when launcher exits.
**Fix**: Run `net.runelite.client.RuneLite` directly via `exec java -cp ...` bypassing the launcher.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [SHELL] 2026-02-22: proot-distro install cleans up rootfs on non-zero exit — FIXED
**Pattern**: `proot-distro install ubuntu` returns non-zero due to `/proc/self/fd/1,2` warnings. Rootfs cleaned up.
**Fix**: Manual rootfs extraction fallback with post-extraction DNS/hosts/env config.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [ANDROID] 2026-02-22: SetupOrchestrator isSuccess too strict for proot commands — FIXED
**Pattern**: `isSuccess` checks `exitCode == 0 && error == null`. Proot commands return non-zero exit codes due to harmless `/proc/self/fd` binding warnings. This caused setup scripts to be treated as failed despite completing all steps.
**Fix**: Added success marker check — looks for `"=== Setup complete ==="` in stdout as alternative to exitCode == 0.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

### [ANDROID] 2026-02-22: TermuxResultService onDestroy clears static pendingResults — FIXED
**Pattern**: `onDestroy()` cancelled all deferreds in static `pendingResults` map. Static fields outlive service instances.
**Fix**: Removed deferred cancellation from `onDestroy()`. Added `stopSelfIfIdle()`.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/termux/TermuxResultService.kt

### [SHELL] 2026-02-22: Manual rootfs extraction skips post-install config — FIXED
**Pattern**: Manual tar extraction missing resolv.conf (DNS), hosts, environment. apt-get hangs on DNS.
**Fix**: Script writes resolv.conf, hosts, environment after manual extraction.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [SHELL] 2026-02-22: DEBIAN_FRONTEND=noninteractive required for apt-get in no-PTY mode — FIXED
**Pattern**: debconf prompts hang even with `-y` when stdin is /dev/null.
**Fix**: Added `env DEBIAN_FRONTEND=noninteractive` before bash -c in proot-distro login calls.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/setup-environment.sh

### [SHELL] 2026-02-22: Termux X11 socket at $PREFIX/tmp, not /tmp — FIXED
**Pattern**: X11 socket at `$PREFIX/tmp/.X11-unix/X0`, not `/tmp/.X11-unix/X0`.
**Fix**: Changed socket path to use `$PREFIX/tmp`.
**Ref**: @runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh

### [UX] 2026-02-22: Termux/Termux:X11 workflow confusing — user must manually switch apps — DESIGNED
**Pattern**: User must manually switch from the RuneLite Tablet app to Termux:X11 after tapping Launch. No in-app guidance; context switch is unintuitive.
**Fix**: Kotlin sends CHANGE_PREFERENCE broadcast (fullscreen, no keyboard bar). Shell script polls X11 socket then runs `am start` to bring Termux:X11 to foreground.
**Status**: DESIGNED — implemented in Slice 4+5.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

---

## Rotated 2026-02-23 (Session 29)

### [ANDROID] 2026-02-23: Chrome Custom Tabs onNavigationEvent URL extra is unreliable
**Pattern**: `CustomTabsCallback.onNavigationEvent(NAVIGATION_STARTED)` does not guarantee URL in extras Bundle. Session callback may not attach if Custom Tab launched before `onCustomTabsServiceConnected`.
**Prevention**: Use localhost `ServerSocket` or `jagex:` intent scheme capture — fully under app control, no Chrome dependency.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/AuthRedirectCapture.kt

### [SECURITY] 2026-02-23: Kotlin data class toString() leaks sensitive fields by default
**Pattern**: Auto-generated `toString()` includes ALL fields. Accidental logging exposes plaintext secrets.
**Prevention**: Always add `override fun toString() = "ClassName([REDACTED])"` to data classes with sensitive fields.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/CredentialManager.kt

---

## Rotated 2026-02-23 (Session 30)

### [AUTH] 2026-02-23: Game session API calls use wrong auth method — accessToken vs id_token/sessionId
**Pattern**: `fetchCharacters()` and `createGameSession()` pass `accessToken` as Bearer header. Real flow: POST `{"idToken":"<jwt>"}` to `/sessions` (returns sessionId), then GET `/accounts` with Bearer `<sessionId>`. Order is also reversed — `/sessions` must come before `/accounts`.
**Prevention**: Verify API call signatures against reference implementations (aitoiaita `game_session.rs`).
**Status**: FIXED in Session 30.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

### [AUTH] 2026-02-23: Consent client_id cannot initiate standalone login — returns unsupported_response_type
**Pattern**: Using `1fddee4e-...` consent client_id with `response_type=code` or `id_token code` for the initial OAuth login. Jagex server returns `unsupported_response_type` error. The consent client only works for Step 2 after Step 1 session is established.
**Prevention**: Always use `com_jagex_auth_desktop_launcher` for Step 1. Consent client is Step 2 only.
**Status**: FIXED in Session 30.
**Ref**: `.claude/plans/2026-02-23-oauth-2step-rewrite-design.md`

### [AUTH-BLOCKER] 2026-02-23: OAuth uses wrong client_id for Step 1 — Jagex rejects with "something went wrong"
**Pattern**: `JagexOAuth2Manager.kt` used `1fddee4e-...` (Step 2 consent client) for Step 1. Jagex rejected.
**Prevention**: Implement correct 2-step flow with 2 client IDs.
**Status**: FIXED in Session 30.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/auth/JagexOAuth2Manager.kt

---

## Rotated 2026-02-23 (Session 28)

### [WINDOWS] 2026-02-23: CRLF line endings in shell scripts break shebang on Termux
**Pattern**: Windows git auto-converts LF to CRLF on checkout. Shell scripts deployed to Termux via `cat > file` retain `\r` in the shebang line. Kernel returns ENOENT.
**Prevention**: `.gitattributes` with `*.sh text eol=lf`, defensive `replace("\r", "")` in ScriptManager.
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/ScriptManager.kt, @.gitattributes

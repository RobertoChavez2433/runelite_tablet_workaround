# Security Fixes Plan — 15 Findings (2 P0, 10 P1, 3 P2)

Based on security review by 3 parallel agents covering: Termux IPC, Network/APK/Logging, and Auth/Credentials.

---

## Phase 1: Auth & Credential Hardening

**Files**: `auth/JagexOAuth2Manager.kt`, `auth/CredentialManager.kt`, `auth/AuthRedirectCapture.kt`

### Task 1 (P0): Sanitize OAuth2 error bodies before logging/displaying
- In `JagexOAuth2Manager.kt` at lines ~126, 162, 192, 221, 263: truncate `errorBody` to 200 chars and sanitize token-like patterns before passing to `AppLog.e()`
- In `parseTokenResponse` (~line 283): never pass raw `responseBody` to `OAuthException` — pass only `"Response keys: ${jsonObj.keys.joinToString()}"`
- Add `override fun toString()` to `OAuthException` that excludes `errorBody`

### Task 2 (P1): Add toString() overrides to sensitive data classes
- `JagexCredentials` in `CredentialManager.kt`: `override fun toString() = "JagexCredentials(displayName=$displayName, [REDACTED])"`
- `TokenResponse` in `JagexOAuth2Manager.kt`: `override fun toString() = "TokenResponse(expiresIn=$expiresIn, [REDACTED])"`
- `AuthCodeResult.Success` in `AuthRedirectCapture.kt`: `override fun toString() = "Success(code=***, state=***)"`

### Task 9 (P1): Move access_token to in-memory only storage
- In `CredentialManager.kt`: keep `access_token` in a `@Volatile private var inMemoryAccessToken: String? = null` instead of persisting to EncryptedSharedPreferences
- Remove `putString(KEY_ACCESS_TOKEN, ...)` from `storeTokens()`/`storeGameSession()`
- Update `getCredentials()` to merge in-memory `access_token` with persisted data
- On cold start with no in-memory token, caller must refresh via `refresh_token`

### Task 13 (P2): Bind LocalhostAuthServer to 127.0.0.1 only
- In `AuthRedirectCapture.kt`: change `ServerSocket(0)` to `ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))`

---

## Phase 2: Activity & ViewModel Security

**Files**: `MainActivity.kt`, `setup/SetupViewModel.kt`

### Task 10 (P1): Add redirect URI host validation in onNewIntent
- In `MainActivity.kt` `onNewIntent()`: add early-return guard checking URI host matches expected OAuth2 redirect host before forwarding to ViewModel

### Task 12 (P1): Add onCleared() to SetupViewModel to wipe auth state
- Add `override fun onCleared()` that nulls out `pendingAccessToken`, `codeVerifier`, `authState`, `customTabCapture`

### Task 14 (P2): Make credentialManager private on SetupViewModel
- Change `val credentialManager` to `private val credentialManager`
- Expose only what the UI needs: add `fun getDisplayName(): String?` or a `displayName` property
- Update any UI code that directly accesses `viewModel.credentialManager.*`

---

## Phase 3: IPC & Intent Hardening

**Files**: `installer/ApkInstaller.kt`, `installer/ApkDownloader.kt`, `setup/SetupOrchestrator.kt`, `setup/CleanupManager.kt`, `termux/TermuxResultService.kt`

### Task 11 (P1): Add FLAG_ONE_SHOT to install result PendingIntent
- In `ApkInstaller.kt` `createInstallIntent()`: add `PendingIntent.FLAG_ONE_SHOT` to the flags

### Task 6 (P1): Add APK package name verification before install
- In `ApkInstaller.kt`: before writing to PackageInstaller session, verify APK package name via `PackageManager.getPackageArchiveInfo()`
- Accept an `expectedPackageName` parameter and return `InstallResult.Failure` on mismatch
- Update callers in `SetupOrchestrator.kt` to pass the expected package name

### Task 7 (P1): Delete downloaded APK files after successful installation
- In `SetupOrchestrator.kt` `installPackage()`: add `apkFile.delete()` after successful install

### Task 3 (P1): Add startup cleanup of stale launch_env_*.sh credential files
- In `CleanupManager.kt` `cleanup()`: add deletion of `launch_env_*.sh` files from `context.filesDir`

### Task 15 (P2): Scrub TermuxResultService stdout/stderr previews for credentials
- In `TermuxResultService.kt`: add scrubbing for patterns like `JX_SESSION_ID=`, `JX_ACCESS_TOKEN=`, `Bearer ` before logging stdout/stderr previews

---

## Phase 4: Network & Build Hardening

**Files**: `res/xml/network_security_config.xml` (new), `AndroidManifest.xml`, `build.gradle.kts`, `proguard-rules.pro`, `logging/AppLog.kt`

### Task 4 (P1): Create network_security_config.xml blocking cleartext traffic
- Create `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="false"` and localhost exception
- Add `android:networkSecurityConfig="@xml/network_security_config"` to `<application>` in AndroidManifest.xml

### Task 5 (P1): Enable minification and add ProGuard rules for release builds
- In `build.gradle.kts`: set `isMinifyEnabled = true` and `isShrinkResources = true` for release
- In `proguard-rules.pro`: add `-assumenosideeffects` rules to strip `Log.v()` and `Log.d()`
- Add keep rules for kotlinx-serialization and OkHttp as needed

### Task 8 (P1): Gate AppLog file logging behind BuildConfig.DEBUG
- In `AppLog.kt`: gate file logging behind `BuildConfig.DEBUG` — in release, only write error-level to file
- Add `BuildConfig.DEBUG` checks to HTTP response body previews in `ApkDownloader.kt`

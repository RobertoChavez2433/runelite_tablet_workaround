# Clean Architecture Refactor & Testing Pipeline Spec

**Date**: 2026-04-14
**Branch**: TBD (from `spike/direct-android-surface`)
**Goal**: Modularize codebase into testable domain layers, establish device-free testing pipeline, enable fast iteration toward 120fps target.

## Problem Statement

The codebase has 45 Kotlin files (~4,600 LoC) with zero tests, 2 interfaces out of 43 classes, 5 global singletons with mutable state, and tight Android framework coupling throughout. Every change requires deploying to a physical Samsung Tab S10 Ultra to verify. This makes iteration toward the 120fps target painfully slow.

### Current State (Measured)

| Metric | Value |
|--------|-------|
| Kotlin files | 45 |
| Kotlin LoC | ~4,600 |
| Interfaces | 2 (`PresentationBackend`, `SetupActions`) |
| Test files | 0 |
| Test coverage | 0% |
| God classes (>500 LoC) | 5 (`SetupViewModel`, `SetupOrchestrator`, `GeckoAuthActivity`, `HybridX11HostActivity`, `JagexOAuth2Manager`) |
| Global mutable singletons | 5 (`AppLog`, `InstallResultReceiver.pendingResults`, `TermuxResultService.pendingResults`, `ApkInstaller.onNeedsUserAction`, `ExternalXlorieLoader.result`) |
| Classes with no interface | 41 of 43 |
| CI/CD pipeline | None |

### Why This Blocks 120fps

The 120fps work (spec: `specs/2026-03-16-presentation-pipeline-120fps-spec.md`) requires changes to the rendering pipeline, frame pacing, and GPU configuration. Without tests, every change to `HybridX11Bridge`, `HybridX11HostActivity`, `SessionHealthMonitor`, or the launch scripts requires a full deploy-to-device cycle (~3-5 minutes per iteration). With a test harness, we can validate frame timing logic, state transitions, and configuration changes in seconds on a laptop.

---

## Hard Constraint: 50-Line Bootstrap Limit

**All app initializers, providers, factories, and bootstrap methods must be ≤50 lines.**

### Bootstrap Audit Checklist

Already passing (no changes needed):
- [x] `RuneLiteTabletApp.onCreate()` — 17 lines
- [x] `RuneLiteTabletApp` (full class) — 49 lines
- [x] `MainActivity.onCreate()` — 16 lines
- [x] `SetupViewModel.Factory.create()` — 33 lines
- [x] `RuneLiteSessionService.onCreate()` — 7 lines
- [x] `RuneLiteSessionService.companion` — 20 lines
- [x] `GeckoAuthActivity.companion` — 36 lines
- [x] `AppLog.init()` — 15 lines
- [x] `ExternalXlorieLoader.ensureLoaded()` — ~40 lines

Must fix:
- [ ] `MainActivity` (full class) — 78 LoC → slim to ≤50 by extracting lifecycle binding
- [ ] `GeckoAuthActivity.onCreate()` — ~80 lines → extract `OAuthFlowCoordinator` + `GeckoSessionManager`
- [ ] `HybridX11HostActivity.onCreate()` — ~60 lines → extract `LorieViewFactory` + `X11AttachmentController`
- [ ] `AppLog` (full object) — 283 LoC → extract `LogFileWriter` + `PerfSnapshots` to own files, slim to ≤50

Must create within limit:
- [ ] `di/AppContainer` — ≤50 lines, delegates to modules
- [ ] `di/CoreModule` — ≤50 lines
- [ ] `di/TermuxModule` — ≤50 lines
- [ ] `di/AuthModule` — ≤50 lines
- [ ] `di/InstallerModule` — ≤50 lines
- [ ] `di/SetupModule` — ≤50 lines
- [ ] `di/SessionModule` — ≤50 lines
- [ ] `testutil/TestAppContainer` — ≤50 lines

### Rules

1. **No initializer, `onCreate()`, `Factory.create()`, or bootstrap method exceeds 50 lines of executable code** (excluding blank lines, comments, imports).
2. **No provider/container class exceeds 50 lines.** If `AppContainer` exceeds 50, split into `CoreModule`, `AuthModule`, `SetupModule` etc. — each ≤50 lines.
3. **Every factory/provider method is a one-liner call.** Construction logic lives in the class itself or a dedicated builder, not in the wiring code.
4. **Constructors take interfaces, not implementations.** The only place that knows about concrete types is the DI module.
5. **Test doubles wire identically.** `TestAppContainer` must be a drop-in replacement with zero extra configuration beyond swapping implementations.

### AppContainer Design (≤50 lines, split into modules)

```kotlin
// di/AppContainer.kt — ≤50 lines, delegates to modules
class AppContainer(context: Context) {
    val core = CoreModule(context)
    val auth = AuthModule(core.httpClient, core.logger)
    val termux = TermuxModule(context, core.logger)
    val installer = InstallerModule(context, core.httpClient, core.logger)
    val setup = SetupModule(termux.commandRunner, termux.packageChecker,
        installer.apkDownloader, installer.apkInstaller, core.logger)
    val session = SessionModule(termux.commandRunner, core.logger)
}

// di/CoreModule.kt — ≤50 lines
class CoreModule(context: Context) {
    val logger: Logger = AppLog.also { it.init(context) }
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

// di/TermuxModule.kt — ≤50 lines
class TermuxModule(context: Context, logger: Logger) {
    val commandRunner: CommandRunner = TermuxCommandRunner(context, logger)
    val packageChecker: PackageChecker = TermuxPackageHelper(context)
}

// di/AuthModule.kt — ≤50 lines
class AuthModule(httpClient: OkHttpClient, logger: Logger) {
    val credentialStore: CredentialStore by lazy { EncryptedCredentialStore(context) }
    val oauthManager: JagexOAuth2Manager by lazy { JagexOAuth2Manager(httpClient, logger) }
}

// di/InstallerModule.kt — ≤50 lines
class InstallerModule(context: Context, httpClient: OkHttpClient, logger: Logger) {
    val apkDownloader: ApkDownloader = ApkDownloader(context, httpClient, logger)
    val apkInstaller: ApkInstaller = ApkInstaller(context, logger)
}

// di/SetupModule.kt — ≤50 lines
class SetupModule(
    commandRunner: CommandRunner,
    packageChecker: PackageChecker,
    apkDownloader: ApkDownloader,
    apkInstaller: ApkInstaller,
    logger: Logger
) {
    val verifier = SetupVerifier(commandRunner, logger)
    val reconciler = MarkerReconciler(commandRunner, logger)
    val stepRunner = SetupStepRunner(commandRunner, packageChecker, apkDownloader, apkInstaller, logger)
    val stateStore: SetupStateStore by lazy { SetupStateStore(context) }
    val orchestrator = SetupOrchestrator(stepRunner, verifier, reconciler, stateStore, logger)

    fun viewModelFactory() = SetupViewModel.Factory(orchestrator, ...)
}
```

### Test Container (mirrors production, ≤50 lines)

```kotlin
// testutil/TestAppContainer.kt
class TestAppContainer {
    val core = TestCoreModule()
    val termux = TestTermuxModule()
    val auth = TestAuthModule()
    // Each test module ≤50 lines, uses fakes
}

class TestCoreModule {
    val logger = TestLogger()
    val httpClient = MockWebServer().also { it.start() }.let { /* OkHttpClient pointing at it */ }
}

class TestTermuxModule {
    val commandRunner = FakeCommandRunner()
    val packageChecker = FakePackageChecker()
}
```

---

## Architecture Target

### Layer Separation

```
com.runelitetablet/
  domain/                          # PURE KOTLIN — zero Android imports
    command/
      CommandRunner.kt             # interface
      CommandResult.kt             # data class
    auth/
      CredentialStore.kt           # interface
      OAuthTokens.kt              # data class
      SessionValidation.kt        # sealed class (existing, moved)
      AuthResult.kt               # sealed class
    setup/
      SetupStep.kt                # sealed class (existing, moved)
      SetupState.kt               # data class
      SetupStepExecutor.kt        # interface
    installer/
      PackageChecker.kt           # interface
      DownloadProgress.kt         # data class
      InstallResult.kt            # sealed class (existing, moved)
    session/
      SessionState.kt             # sealed class (existing, moved)
      HealthCheckResult.kt        # sealed class
    presentation/
      PresentationBackend.kt      # interface (existing, moved)
      FrameTimingTracker.kt       # pure Kotlin frame stats
    logging/
      Logger.kt                   # interface

  data/                            # ANDROID — implements domain interfaces
    termux/
      TermuxCommandRunner.kt      # implements CommandRunner
      TermuxResultService.kt      # BroadcastReceiver (keeps static map, but behind interface)
      TermuxPackageHelper.kt      # implements PackageChecker
    auth/
      EncryptedCredentialStore.kt  # implements CredentialStore
      JagexOAuth2Manager.kt       # uses CommandRunner, CredentialStore
      PkceHelper.kt               # pure Kotlin (stays as-is)
      LaunchEnvDeployer.kt        # uses CommandRunner, CredentialStore
    installer/
      ApkDownloader.kt            # uses HttpClient interface or OkHttp interceptor
      ApkInstaller.kt             # Android PackageInstaller wrapper
      InstallResultReceiver.kt    # BroadcastReceiver
    session/
      RuneLiteSessionService.kt   # Foreground service
      SessionHealthMonitor.kt     # uses CommandRunner
    presentation/
      DirectSurfaceProbeActivity.kt
      DirectSurfaceProbeBackend.kt
      HybridX11PresentationBackend.kt
      TermuxX11PresentationBackend.kt
    presentation/hybrid/
      ExternalXlorieLoader.kt
      HybridInputController.kt
      HybridX11Bridge.kt
      HybridX11HostActivity.kt
      HybridX11TestReceiver.kt
      TermuxX11StartReceiver.kt
    logging/
      AppLog.kt                   # implements Logger
    cleanup/
      CleanupManager.kt
    setup/
      ScriptManager.kt            # uses CommandRunner
      SetupOrchestrator.kt        # uses domain interfaces only
      SetupStateStore.kt          # SharedPreferences wrapper

  presentation/                    # UI — Compose, ViewModels, Activities
    setup/
      SetupViewModel.kt           # depends on domain interfaces
      SetupScreen.kt              # Compose UI
    auth/
      GeckoAuthActivity.kt        # OAuth browser
    ui/
      components/StepItem.kt
      screens/CharacterSelectScreen.kt
      screens/LogViewerScreen.kt
      screens/SettingsScreen.kt
      theme/Theme.kt

  di/
    AppContainer.kt               # Manual DI wiring
```

### Layer Boundary Rules (verify at each phase gate)

- [ ] `domain/` has ZERO Android imports — no `Context`, no `Intent`, no `android.*`, only `kotlinx.coroutines` and stdlib
- [ ] `data/` implements `domain/` interfaces — may import Android SDK freely
- [ ] `presentation/` depends on `domain/` interfaces — ViewModels never reference `data/` implementations directly
- [ ] `di/AppContainer` is the ONLY place that wires `data/` implementations to `domain/` interfaces
- [ ] No class exceeds 200 LoC, no method exceeds 200 lines — split by extracting collaborators

---

## Interface Extraction Plan

### Priority 1 — Blocks all testing

- [ ] **P1.1** Extract `CommandRunner` interface from `TermuxCommandRunner`
  - Interface in `domain/command/CommandRunner.kt`
  - Methods: `suspend fun execute(commandPath, arguments, workdir, background, stdin, timeoutMs): CommandResult`
  - `TermuxCommandRunner` moves to `data/termux/` and `implements CommandRunner`
  - Create `FakeCommandRunner` in test sources

- [ ] **P1.2** Extract `CredentialStore` interface from `CredentialManager`
  - Interface in `domain/auth/CredentialStore.kt`
  - Methods: `saveTokens()`, `getAccessToken()`, `getRefreshToken()`, `getGameSession()`, `clear()`
  - `EncryptedCredentialStore` wraps existing `CredentialManager` logic
  - Create `FakeCredentialStore` (in-memory HashMap)

- [ ] **P1.3** Extract `Logger` interface from `AppLog`
  - Interface in `domain/logging/Logger.kt`
  - Methods: `info(tag, msg)`, `warn(tag, msg)`, `error(tag, msg, throwable?)`, `perf(msg)`
  - `AppLog` implements `Logger` (keeps file writing)
  - Create `TestLogger` (collects log entries in a list)
  - Inject `Logger` into all classes that currently call `AppLog` directly

- [ ] **P1.4** Extract `PackageChecker` interface from `TermuxPackageHelper`
  - Interface in `domain/installer/PackageChecker.kt`
  - Methods: `isInstalled(packageName): Boolean`, `getVersionCode(packageName): Long?`
  - Create `FakePackageChecker`

### Priority 2 — Enables ViewModel testing

- [ ] **P2.1** Create `AppContainer` DI class
  - Central wiring in `di/AppContainer.kt`
  - All interfaces resolved via `by lazy {}` singletons
  - `SetupViewModel.Factory` receives interfaces, not concrete classes
  - `RuneLiteTabletApp.container` replaces scattered construction

- [ ] **P2.2** Make `SetupOrchestrator` depend on interfaces only
  - Constructor takes `CommandRunner`, `PackageChecker`, `Logger`, `SetupStateStore`
  - Remove direct `Context` dependency (pass only what's needed via interfaces)
  - Extract `PermissionChecker` interface for permission-related logic

- [ ] **P2.3** Inject `CoroutineDispatcher` into all classes using `Dispatchers.IO`
  - Add `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` parameter
  - Affected: `SetupOrchestrator`, `ApkDownloader`, `JagexOAuth2Manager`, `CredentialManager`, `SessionHealthMonitor`

### Priority 3 — Enables full coverage

- [ ] **P3.1** Extract `SetupVerifier` from `SetupOrchestrator`
  - Methods: `verifyProot()`, `verifyJava()`, `verifyRuneLite()`, `verifyX11()`
  - Currently lines 594-628 in `SetupOrchestrator.kt`
  - Pure logic: runs commands and interprets output

- [ ] **P3.2** Extract `MarkerReconciler` from `SetupOrchestrator`
  - Methods: `reconcileMarkers(commandRunner): Map<SetupStep, Boolean>`
  - Currently lines 691-773 in `SetupOrchestrator.kt`
  - Pure logic: parses check-markers.sh output

- [ ] **P3.3** Extract `OAuthFlowCoordinator` from `GeckoAuthActivity`
  - Pure Kotlin class handling OAuth state machine
  - Activity becomes a thin shell: create GeckoView, delegate navigation events to coordinator
  - Coordinator is testable without Activity

- [ ] **P3.4** Extract `X11AttachmentController` from `HybridX11HostActivity`
  - Manages binder connection state machine (lines 197-288)
  - Interface for `X11ServiceConnector` wrapping binder calls
  - Controller testable with fake connector

- [ ] **P3.5** Eliminate global mutable singletons
  - `InstallResultReceiver.pendingResults` → inject `ResultRegistry` interface
  - `TermuxResultService.pendingResults` → same pattern
  - `ApkInstaller.onNeedsUserAction` → constructor-injected callback
  - `ExternalXlorieLoader.result` → instance-based loader, inject via AppContainer

---

## God Class Decomposition

### SetupOrchestrator (790 LoC → 4 classes, ~200 LoC each)

- [ ] Extract `SetupStepRunner` — execute individual setup steps (proot, java, runelite, gpu) — source lines 237-305
- [ ] Extract `SetupVerifier` — verify installation state — source lines 594-628
- [ ] Extract `MarkerReconciler` — parse and reconcile completion markers — source lines 691-773
- [ ] Slim `SetupOrchestrator` — orchestrate step ordering and state transitions only — remainder
- [ ] Verify `SetupOrchestrator` ≤200 LoC after extraction

### SetupViewModel (1,147 LoC → 3 classes, ≤200 LoC each)

- [ ] Extract `AuthCoordinator` — OAuth flow, token refresh, session validation — auth-related methods
- [ ] Extract `LaunchCoordinator` — pre-launch checks, env deployment, session start — launch-related methods
- [ ] Slim `SetupViewModel` — UI state aggregation, screen routing only — remainder
- [ ] Verify `SetupViewModel` ≤200 LoC after extraction

### GeckoAuthActivity (512 LoC → 2 classes)

- [ ] Extract `OAuthFlowCoordinator` — state machine for 2-step Jagex OAuth (pure Kotlin)
- [ ] Slim `GeckoAuthActivity` — GeckoView lifecycle + navigation delegate only
- [ ] Verify `GeckoAuthActivity.onCreate()` ≤50 lines after extraction
- [ ] Verify `GeckoAuthActivity` ≤200 LoC after extraction

### HybridX11HostActivity (351 LoC → 2 classes)

- [ ] Extract `X11AttachmentController` — binder connection state machine, retry logic
- [ ] Slim `HybridX11HostActivity` — LorieView setup, lifecycle, input routing only
- [ ] Verify `HybridX11HostActivity.onCreate()` ≤50 lines after extraction
- [ ] Verify `HybridX11HostActivity` ≤200 LoC after extraction

### AppLog (283 LoC → 3 files, ≤50 LoC for AppLog object)

- [ ] Extract `LogFileWriter` to its own file `data/logging/LogFileWriter.kt`
- [ ] Extract `PerfSnapshots` to its own file `data/logging/PerfSnapshots.kt`
- [ ] Slim `AppLog` object to ≤50 lines (delegates to `LogFileWriter` + implements `Logger`)
- [ ] Verify `AppLog` ≤50 LoC after extraction

---

## Testing Strategy

### Test Pyramid

```
                    /\
                   /  \  Instrumented (androidTest/)
                  /    \  — Smoke tests only, require emulator
                 /------\  — 5-10 tests max
                /        \
               /  Robolec. \  Compose UI tests (test/ + Robolectric)
              /    (test/)   \  — Screen rendering, navigation
             /--------------\  — 10-20 tests
            /                \
           /   Pure JVM       \  Unit tests (test/)
          /     (test/)        \  — ViewModels, use cases, state machines
         /                      \  — 50-100 tests, runs in <10 seconds
        /________________________\
```

### Test Dependencies (add to build.gradle.kts)

```kotlin
// Unit testing — core
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
testImplementation("app.cash.turbine:turbine:1.2.1")

// Real HTTP testing — MockWebServer is a real HTTP server, not a mock
// Lets us test real OkHttp + real JagexOAuth2Manager + real ApkDownloader
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

// Compose UI testing with Robolectric (Phase 4+)
testImplementation("org.robolectric:robolectric:4.13")
testImplementation("androidx.compose.ui:ui-test-junit4")
testImplementation("androidx.compose.ui:ui-test-manifest")

// Instrumented — smoke tests only
androidTestImplementation("androidx.test:runner:1.6.1")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
```

**NOT adding**: Mockito, MockK, or any mocking framework. We test real components. The 5 fakes above are hand-written, trivial implementations for Android-boundary deps only.

### Testing Philosophy: Real Components First, Fakes Only at Android Boundary

**Rule: Use real implementations everywhere possible. Only fake what literally requires Android hardware or an installed app.**

Components tested as real implementations (no fakes):
- [ ] `JagexOAuth2Manager` — real OkHttp against `MockWebServer` (a real local HTTP server, not a mock)
- [ ] `ApkDownloader` — real OkHttp against `MockWebServer`
- [ ] `PkceHelper` — real, pure Kotlin
- [ ] `OAuthFlowCoordinator` — real, pure state machine
- [ ] `MarkerReconciler` — real, pure string parsing
- [ ] `SetupVerifier` — real, pure output interpretation
- [ ] `X11AttachmentController` — real, pure state machine
- [ ] `AuthCoordinator` / `LaunchCoordinator` — real, with fakes only for Android-boundary deps
- [ ] `FrameTimingTracker` — real, pure math
- [ ] `SetupOrchestrator` — real orchestration logic, fakes only for Android I/O

Minimum required fakes (Android boundary only — 5 total):
- [ ] `FakeCommandRunner` replaces `TermuxCommandRunner` — can't send Android Intents to Termux on laptop
- [ ] `FakeCredentialStore` replaces `CredentialManager` — can't use Android Keystore hardware encryption
- [ ] `FakePackageChecker` replaces `TermuxPackageHelper` — can't query Android PackageManager
- [ ] `FakeSetupStateStore` replaces `SetupStateStore` — can't use Android SharedPreferences
- [ ] `PrintLogger` replaces `AppLog` — can't use Android `Log` + `SystemClock`

NOT faking:
- ~~`FakeOAuthManager`~~ — use real `JagexOAuth2Manager` with `MockWebServer`
- ~~`FakeApkDownloader`~~ — use real `ApkDownloader` with `MockWebServer`
- ~~Any extracted pure-Kotlin class~~ — always test the real thing

### Test Infrastructure Classes

- [ ] **T1** `MainDispatcherRule` — replaces `Dispatchers.Main` with `UnconfinedTestDispatcher` for ViewModel tests
- [ ] **T2** `FakeCommandRunner` — Android boundary fake: records commands, returns configurable `CommandResult`
- [ ] **T3** `FakeCredentialStore` — Android boundary fake: in-memory HashMap token storage
- [ ] **T4** `FakePackageChecker` — Android boundary fake: configurable installed/not-installed
- [ ] **T5** `PrintLogger` — Android boundary fake: prints to stdout (or collects in list for assertions)
- [ ] **T6** `FakeSetupStateStore` — Android boundary fake: in-memory step completion tracking

### Test Files To Create

All under `runelite-tablet/app/src/test/java/com/runelitetablet/`:

Test utilities:
- [ ] `testutil/MainDispatcherRule.kt`
- [ ] `testutil/FakeCommandRunner.kt`
- [ ] `testutil/FakeCredentialStore.kt`
- [ ] `testutil/FakePackageChecker.kt`
- [ ] `testutil/FakeSetupStateStore.kt`
- [ ] `testutil/PrintLogger.kt`

Domain tests (pure Kotlin, real components):
- [ ] `domain/command/CommandResultTest.kt`
- [ ] `domain/auth/SessionValidationTest.kt`

Data tests (real components, fakes at Android boundary only):
- [ ] `data/auth/JagexOAuth2ManagerTest.kt` — real manager + real OkHttp + MockWebServer
- [ ] `data/auth/PkceHelperTest.kt` — real, pure logic
- [ ] `data/auth/LaunchEnvDeployerTest.kt` — real deployer + FakeCommandRunner
- [ ] `data/installer/ApkDownloaderTest.kt` — real downloader + real OkHttp + MockWebServer
- [ ] `data/setup/SetupVerifierTest.kt` — real verifier + FakeCommandRunner
- [ ] `data/setup/MarkerReconcilerTest.kt` — real reconciler + FakeCommandRunner
- [ ] `data/setup/SetupOrchestratorTest.kt` — real orchestrator, state transitions
- [ ] `data/session/SessionHealthMonitorTest.kt` — real monitor + FakeCommandRunner

Presentation tests:
- [ ] `presentation/setup/SetupViewModelTest.kt` — real VM + boundary fakes + Turbine
- [ ] `presentation/setup/AuthCoordinatorTest.kt` — real coordinator, OAuth state machine
- [ ] `presentation/setup/LaunchCoordinatorTest.kt` — real coordinator, pre-launch sequence
- [ ] `presentation/auth/OAuthFlowCoordinatorTest.kt` — real coordinator, 2-step state machine
- [ ] `presentation/hybrid/X11AttachmentControllerTest.kt` — real controller, retry logic

### Key Test Scenarios

#### SetupViewModel (highest value)
- [ ] Initial state is Setup screen with all steps pending
- [ ] Steps execute in order, each updating state
- [ ] Failed step marks step as failed, does not advance
- [ ] Retry failed step re-executes only that step
- [ ] Setup completion transitions to Login screen
- [ ] Auth success transitions to CharacterSelect
- [ ] Launch sequence: check update → deploy env → start session
- [ ] Cancel during launch aborts coroutine and resets state
- [ ] Session stop resets to appropriate screen

#### SetupOrchestrator (state machine)
- [ ] Fresh install runs all steps in order
- [ ] Cached steps are skipped (marker reconciliation)
- [ ] Version change invalidates cache
- [ ] Permission step waits for callback before advancing
- [ ] Termux install step detects already-installed
- [ ] GPU step is non-blocking (fire-and-forget)
- [ ] Verification step checks proot, java, runelite, x11

#### JagexOAuth2Manager (network)
- [ ] Token exchange sends correct POST body
- [ ] Token refresh uses refresh_token grant
- [ ] Expired token triggers refresh
- [ ] Invalid refresh token returns SessionValidation.Invalid
- [ ] JWT parsing extracts correct claims
- [ ] Network timeout returns appropriate error
- [ ] CancellationException propagates (not swallowed)

#### SessionHealthMonitor (polling)
- [ ] Running state when sentinel file exists
- [ ] Debounces 3 consecutive STOPPED before transitioning
- [ ] Startup uses 5s interval, running uses 60s
- [ ] Cancellation stops polling cleanly

#### OAuthFlowCoordinator (state machine)
- [ ] Step 1: authorize URL constructed correctly
- [ ] Step 1: redirect parsed into auth code
- [ ] Step 2: consent URL constructed from step 1 tokens
- [ ] Step 2: redirect completes flow
- [ ] Error at step 1 does not advance to step 2
- [ ] Timeout at either step returns error

#### X11AttachmentController (retry logic)
- [ ] Successful binder attachment on first try
- [ ] Retry after 250ms on binder unavailable
- [ ] Binder death triggers reconnection
- [ ] Max retries exceeded transitions to error state
- [ ] Cancellation stops retry loop

---

## 120fps Testing Without Device

### Frame Timing Unit Tests (Pure JVM)

The `FrameTimingTracker` class (new, in `domain/presentation/`) tracks frame intervals as pure math — no Android Surface needed:

```kotlin
class FrameTimingTracker {
    private val frameTimes = LongArray(240)
    private var index = 0
    private var count = 0

    fun recordFrame(timestampNs: Long) { ... }
    fun getAverageFps(windowSize: Int = 120): Double { ... }
    fun getFrameTimePercentile(p: Double): Double { ... }
    fun getJankFrameCount(thresholdMs: Double = 12.0): Int { ... }
}
```

Tests validate:
- [ ] 120 frames at 8.33ms intervals = 120fps
- [ ] Mixed intervals correctly averaged
- [ ] Jank detection flags frames >12ms
- [ ] P99 frame time calculation
- [ ] Rolling window discards old samples

### VBlank Interval Tests (Pure JVM)

Test the vblank math from `InitOutput.c` logic:
- [ ] 120Hz → 8333µs interval
- [ ] 60Hz → 16666µs interval
- [ ] Rounding error at 119Hz is <1%

### GPU Configuration Tests (Script Output Parsing)

Test launch-runelite.sh configuration output parsing:
- [ ] Adreno GPU detection sets `MESA_LOADER_DRIVER_OVERRIDE=zink`
- [ ] Mali GPU detection sets `GALLIUM_DRIVER=virpipe`
- [ ] FPS target defaults to 120 when unlocked
- [ ] VSync mode correctly propagated to RuneLite config

### MockWebServer for OAuth + Download Flows

- [ ] GitHub Releases API response parsing
- [ ] APK download with progress tracking
- [ ] OAuth token exchange round-trip
- [ ] Network error handling (timeout, 500, malformed JSON)

---

## DI Container Design (50-Line Module Split)

See "Hard Constraint: 50-Line Bootstrap Limit" section above for the full `AppContainer` + module design. Verify each module:

- [ ] `di/AppContainer` — composes all modules, exposes ViewModel factories — ≤50 lines
- [ ] `di/CoreModule` — `Logger`, `OkHttpClient` — ≤50 lines
- [ ] `di/TermuxModule` — `CommandRunner`, `PackageChecker` — ≤50 lines
- [ ] `di/AuthModule` — `CredentialStore`, `JagexOAuth2Manager` — ≤50 lines
- [ ] `di/InstallerModule` — `ApkDownloader`, `ApkInstaller` — ≤50 lines
- [ ] `di/SetupModule` — `SetupOrchestrator` + extracted collaborators — ≤50 lines
- [ ] `di/SessionModule` — `SessionHealthMonitor`, service helpers — ≤50 lines
- [ ] `testutil/TestAppContainer` — mirrors production with all fakes — ≤50 lines

---

## Execution Order (Phased)

### Phase 1: Foundation (Week 1)
_Goal: Enable first test to run_

- [ ] 1.1 Create `src/test/java/com/runelitetablet/testutil/` directory
- [ ] 1.2 Add test dependencies to `build.gradle.kts`
- [ ] 1.3 Create `MainDispatcherRule`
- [ ] 1.4 Extract `Logger` interface, create `TestLogger`
- [ ] 1.5 Extract `CommandRunner` interface, create `FakeCommandRunner`
- [ ] 1.6 Extract `CredentialStore` interface, create `FakeCredentialStore`
- [ ] 1.7 Extract `PackageChecker` interface, create `FakePackageChecker`
- [ ] 1.8 Write `PkceHelperTest` (already pure Kotlin — easiest first test)
- [ ] 1.9 Verify `./gradlew test` runs and passes

### Phase 2: DI Container + Bootstrap Enforcement (Week 1-2)
_Goal: ViewModels depend on interfaces; every initializer/provider ≤50 lines_

- [ ] 2.1 Create `di/CoreModule`, `di/TermuxModule`, `di/AuthModule`, `di/InstallerModule`, `di/SetupModule`, `di/SessionModule` — each ≤50 lines
- [ ] 2.2 Create `di/AppContainer` composing all modules — ≤50 lines
- [ ] 2.3 Update `RuneLiteTabletApp` to create container (already ≤50, just swap httpClient source)
- [ ] 2.4 Move `SetupViewModel.Factory` to accept interfaces from container — Factory.create() ≤50 lines
- [ ] 2.5 Slim `MainActivity` to ≤50 lines (extract lifecycle binding to extension or inline)
- [ ] 2.6 Split `AppLog` (283 LoC): extract `LogFileWriter` to own file, extract `PerfSnapshots` to own file — `AppLog` object ≤50 lines
- [ ] 2.7 Inject `CoroutineDispatcher` into all IO-using classes
- [ ] 2.8 Create `FakeSetupStateStore`
- [ ] 2.9 Create `testutil/TestAppContainer` with all test modules — each ≤50 lines
- [ ] 2.10 **Enforcement gate**: run line-count check — no file in `di/`, no `Application`, no `Factory`, no `onCreate()` method exceeds 50 executable lines
- [ ] 2.11 Verify app still works on device (regression check)

### Phase 3: God Class Decomposition (Week 2)
_Goal: No class >200 LoC_

- [ ] 3.1 Extract `SetupVerifier` from `SetupOrchestrator`
- [ ] 3.2 Extract `MarkerReconciler` from `SetupOrchestrator`
- [ ] 3.3 Extract `SetupStepRunner` from `SetupOrchestrator`
- [ ] 3.4 Extract `AuthCoordinator` from `SetupViewModel`
- [ ] 3.5 Extract `LaunchCoordinator` from `SetupViewModel`
- [ ] 3.6 Extract `OAuthFlowCoordinator` from `GeckoAuthActivity`
- [ ] 3.7 Extract `X11AttachmentController` from `HybridX11HostActivity`
- [ ] 3.8 Verify app still works on device (regression check)

### Phase 4: Core Test Suite (Week 2-3)
_Goal: 50+ tests running in <10 seconds_

- [ ] 4.1 `SetupViewModelTest` — all state transition scenarios
- [ ] 4.2 `SetupOrchestratorTest` — step ordering, caching, retry
- [ ] 4.3 `SetupVerifierTest` — proot/java/runelite/x11 verification
- [ ] 4.4 `MarkerReconcilerTest` — marker parsing
- [ ] 4.5 `SessionHealthMonitorTest` — polling, debouncing
- [ ] 4.6 `OAuthFlowCoordinatorTest` — 2-step state machine
- [ ] 4.7 `X11AttachmentControllerTest` — retry logic
- [ ] 4.8 `AuthCoordinatorTest` — token refresh, session validation
- [ ] 4.9 `LaunchCoordinatorTest` — pre-launch sequence

### Phase 5: Real-Component Integration Tests (Week 3)
_Goal: OAuth and download flows tested with real OkHttp against MockWebServer_

- [ ] 5.1 `JagexOAuth2ManagerTest` — real manager, real OkHttp, MockWebServer returns crafted JSON responses
- [ ] 5.2 `ApkDownloaderTest` — real downloader, real OkHttp, MockWebServer serves fake APK bytes with progress
- [ ] 5.3 `LaunchEnvDeployerTest` — real deployer with FakeCommandRunner + FakeCredentialStore

### Phase 6: Singleton Elimination (Week 3)
_Goal: No global mutable state_

- [ ] 6.1 `InstallResultReceiver.pendingResults` → injected `ResultRegistry`
- [ ] 6.2 `TermuxResultService.pendingResults` → same pattern
- [ ] 6.3 `ApkInstaller.onNeedsUserAction` → constructor-injected callback
- [ ] 6.4 `ExternalXlorieLoader.result` → instance-based, via AppContainer

### Phase 7: Performance Testing Infrastructure (Week 3-4)
_Goal: Frame timing testable without device_

- [ ] 7.1 Create `FrameTimingTracker` in `domain/presentation/`
- [ ] 7.2 Write `FrameTimingTrackerTest` (120fps math, jank detection)
- [ ] 7.3 Hook `FrameTimingTracker` into `HybridX11HostActivity`
- [ ] 7.4 Create `XloriePerf` log parser (reads renderer.c perf output format)
- [ ] 7.5 Write `XloriePerfParserTest` (parse perf stats, validate thresholds)
- [ ] 7.6 Create GPU config validation tests (script output parsing)
- [ ] 7.7 Verify 120fps target on device with new instrumentation

### Phase 8: CI/CD (Week 4)
_Goal: Tests run on every push_

- [ ] 8.1 Create `.github/workflows/android-tests.yml`
- [ ] 8.2 Configure `./gradlew test` in CI
- [ ] 8.3 Add test result reporting
- [ ] 8.4 Add branch protection requiring green tests

---

## Verification Gates

Each phase has a pass/fail gate before proceeding:

- [ ] **Phase 1 gate**: `./gradlew test` exits 0 with ≥1 test
- [ ] **Phase 2 gate**: App boots on device; every DI module, factory, initializer, and `onCreate()` ≤50 lines
- [ ] **Phase 3 gate**: No class >200 LoC, no method >200 lines, `./gradlew test` still passes
- [ ] **Phase 4 gate**: ≥50 tests, all pass in <10 seconds
- [ ] **Phase 5 gate**: OAuth + download flows tested with real components against MockWebServer
- [ ] **Phase 6 gate**: Zero global mutable state, all tests pass
- [ ] **Phase 7 gate**: Frame timing logic tested, XloriePerf parsed
- [ ] **Phase 8 gate**: GitHub Actions runs tests on every push

## Final Acceptance Criteria

- [ ] Zero Android imports in `domain/` package
- [ ] Every class with business logic has a corresponding test
- [ ] `./gradlew test` runs 50+ tests in <10 seconds on laptop
- [ ] No class exceeds 200 LoC
- [ ] No method or function exceeds 200 lines
- [ ] **No initializer, provider, factory, or `onCreate()` exceeds 50 lines**
- [ ] **Every DI module ≤50 lines**
- [ ] No global mutable singletons
- [ ] All ViewModels depend on interfaces, not concrete implementations
- [ ] `CoroutineDispatcher` injected everywhere (no hardcoded `Dispatchers.IO`)
- [ ] CI pipeline blocks merge on test failure
- [ ] 120fps frame timing logic validated without device
- [ ] App verified functional on Samsung Tab S10 Ultra after all changes

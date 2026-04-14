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

---

## Phase 9: Comprehensive Logging System (Addendum — 2026-04-14, Revised 2026-04-14)

**Goal**: Thread a unified, exhaustive logging system through EVERY file in the app — Kotlin and native C — with a single debug endpoint. Every data handoff, state transition, thread switch, fd operation, buffer copy, JNI call, GL state change, and frame event must be observable from one place. This is not optional instrumentation — this is the nervous system of the app. Without it, we are blind to why frames drop, why surfaces go black, why binders die, and why sessions hang.

**Why this matters**: The rendering pipeline crosses 6 process/thread/IPC boundaries. A single silent failure at ANY boundary kills the entire frame path. We've already burned sessions debugging "black screen" (VirGL depth clamp), "0.2 FPS" (Xlorie legacy drawing), and "LOGGING_IN loop" (stale jars). Each of those would have been immediately visible with proper logging at the handoff points. This logging system exists so we NEVER debug blind again.

**Existing infrastructure**: `AppLog` (Logcat + file), `LogFileWriter` (async file writer), `LogViewerScreen` (in-app viewer), `Logger` interface (domain-level), `PrintLogger` (tests). The systematic-debug skill already expects a log endpoint — this system provides it.

### 9.1 — Single Debug Endpoint

The app exposes ONE endpoint that the systematic-debug skill and any external tooling can connect to. All log output — Kotlin, native C (via JNI bridge), shell scripts (via stdout capture) — flows through this single pipe.

- [ ] **9.1.1** Create `debug/DebugLogServer.kt` — lightweight WebSocket server (raw `ServerSocket` + `java.net` — no Ktor dependency) on configurable port (default 8099). This is the endpoint the systematic-debug skill hooks into.
- [ ] **9.1.2** Server lifecycle managed by `AppContainer` — starts in `DEBUG` builds only, stops on app destroy. No global state.
- [ ] **9.1.3** `AppLog` becomes the SOLE Kotlin-side log router: every `Logger` call flows to (a) Logcat, (b) file writer, (c) debug server WebSocket — all three, always, in DEBUG builds.
- [ ] **9.1.4** Structured JSON format over WebSocket — every field mandatory, no optional nulls:
  ```json
  {
    "ts": 1712000000000,
    "elapsed": 5234,
    "level": "D",
    "tag": "FRAME",
    "msg": "fps=118.2 jank=1 p99=9.1ms heap=45MB",
    "thread": "main",
    "correlationId": "launch-001",
    "source": "kotlin",
    "file": "HybridX11HostActivity.kt",
    "line": 97
  }
  ```
- [ ] **9.1.5** Native C logs bridge into same endpoint: `activity.c` and `renderer.c` call `__android_log_print()` which Logcat captures → `DebugLogServer` subscribes to Logcat via `Runtime.exec("logcat -v threadtime -s LorieNative:V")` and re-emits as structured JSON with `"source": "native"`.
- [ ] **9.1.6** Shell script logs bridge: Termux command stdout/stderr captured by `TermuxCommandRunner` already — route to Logger with `"source": "shell"` tag.
- [ ] **9.1.7** HTML/JS viewer served at `http://device-ip:8099/` — real-time WebSocket consumer with:
  - Filter by: tag, level, correlationId, source (kotlin/native/shell), file, thread
  - Highlight: jank frames (red), errors (red), warnings (yellow), handoffs (blue)
  - Timeline view: correlate events across boundaries by timestamp
  - Pause/resume stream without losing buffered events
- [ ] **9.1.8** Dev machine access: `adb forward tcp:8099 tcp:8099` → `http://localhost:8099/`
- [ ] **9.1.9** Programmatic access: systematic-debug skill connects to `ws://localhost:8099/ws` and receives the same JSON stream. No separate API needed — the WebSocket IS the API.

### 9.2 — Logger Interface Enhancements

The current `Logger` interface has 14 methods. It needs to grow to cover every type of event in the graphics pipeline, with correlation threading built into every call.

- [ ] **9.2.1** Add `correlationId: String? = null` parameter to ALL existing Logger methods (`d`, `i`, `w`, `e`, and all convenience methods). This is the backbone of cross-boundary tracing. Every method call in an operation's chain passes the same correlationId.
- [ ] **9.2.2** `frame(fps: Double, jankCount: Int, p99Ms: Double, heapMb: Int, message: String)` — frame timing aggregate, logged every N frames (configurable, default 120 = 1 second at 120fps)
- [ ] **9.2.3** `jank(frameTimeMs: Double, expectedMs: Double, frameNumber: Long, message: String)` — individual jank event, logged EVERY TIME a frame exceeds threshold. Include frame number for correlation with native renderer stats.
- [ ] **9.2.4** `handoff(from: String, to: String, dataType: String, dataSizeBytes: Int, message: String)` — explicit boundary crossing log. Examples: `handoff("OAuthFlowCoordinator", "CredentialManager", "tokens", 256, "step1 tokens saved")`, `handoff("LorieView", "native_renderer", "surface", 0, "surfaceChanged 2960x1848 BGRA_8888")`
- [ ] **9.2.5** `surface(event: String, width: Int, height: Int, format: String, message: String)` — surface lifecycle. Events: `created`, `changed`, `destroyed`, `format_negotiation`, `refresh_rate_set`, `lockCanvas_failed`, `lockCanvas_fallback`
- [ ] **9.2.6** `ipc(direction: String, endpoint: String, latencyMs: Long, message: String)` — IPC operations. Direction: `send`/`receive`/`bidirectional`. Endpoints: `termux_intent`, `binder`, `x11_socket`, `unix_socket`, `broadcast`
- [ ] **9.2.7** `fd(operation: String, fd: Int, path: String, message: String)` — file descriptor lifecycle. Operations: `open`, `close`, `detach`, `transfer`, `leak_suspect`. Critical for X11 socket and binder fd tracking.
- [ ] **9.2.8** `gl(operation: String, errorCode: Int?, message: String)` — GL/EGL state changes. Operations: `init`, `shader_compile`, `texture_upload`, `draw`, `swap`, `error`, `context_lost`
- [ ] **9.2.9** `native(component: String, message: String)` — bridge method for native C logs that arrive via Logcat subscription. Component: `renderer`, `activity`, `buffer`, `xcallback`
- [ ] **9.2.10** `thread(from: String, to: String, operation: String, message: String)` — thread transition tracking. Every `withContext()`, `runOnUiThread()`, `Handler.post()`, `launch()` that crosses dispatchers.
- [ ] **9.2.11** `buffer(operation: String, sizeBytes: Int, format: String, message: String)` — buffer lifecycle. Operations: `allocate`, `copy`, `map`, `unmap`, `free`, `receive_from_socket`. For tracking mmap'd shared memory, ByteBuffer allocations, and X11 buffer transfers.

### 9.3 — Thread Logging Into EVERY Kotlin File

**53 of 82 files have zero logging. This is unacceptable.** Every file that contains logic — not just data classes and interfaces — gets instrumented. The standard is: if data enters, transforms, or leaves a method, it gets logged. Not "log the happy path" — log entries, exits, failures, edge cases, and timing.

#### Setup Orchestration (14 files missing — THE biggest blind spot)
- [ ] **9.3.1** `SetupOrchestrator` — Log: state machine entry/exit for every transition. Step start with step name + index. Step complete with duration + exit status. Step skip with reason (cached/already-done). Step fail with error + retry count. Total orchestration duration on completion. Coroutine cancellation. State snapshots on every transition (not just errors).
- [ ] **9.3.2** `SetupStepRunner` — Log: which step executing, full command path, argument count, pre-execution state check, execution duration (timed with `System.nanoTime()`), exit code, stdout first 500 chars, stderr first 500 chars, whether retry was needed, post-execution state.
- [ ] **9.3.3** `SetupVerifier` — Log: each verification target (proot/java/runelite/x11), raw command used, raw output received, parse result, pass/fail with reason, total verification duration. Log the actual version strings found (java -version output, proot --version, etc.).
- [ ] **9.3.4** `MarkerReconciler` — Log: raw check-markers.sh output, each marker parsed with key-value, markers found vs markers expected, reconciliation delta (what changed since last check), final reconciled state map.
- [ ] **9.3.5** `AuthCoordinator` — Log: auth trigger source (user tap vs auto-refresh), token state before action (has access token? refresh token? session? are they expired?), which auth path taken (fresh login vs refresh vs session reuse), result of each step, credential save confirmation, handoff to next coordinator.
- [ ] **9.3.6** `LaunchCoordinator` — Log: pre-launch checklist items (update check, env deployment, backend selection, session start), each check result, which backend selected and WHY (probe result, user override, fallback), intent construction details (action, extras count, target component), session service start confirmation, handoff to presentation layer.
- [ ] **9.3.7** `SetupViewModel` — Log: every screen state transition with old→new state, every user action (button name, action type), coroutine launch (scope, dispatcher), coroutine cancel (reason), StateFlow emissions that change UI, error states set/cleared.
- [ ] **9.3.8** `SetupActionsImpl` — Log: action received (action name), delegation target (which coordinator/manager), delegation result, callback invocation.
- [ ] **9.3.9** `PermissionHandler` — Log: permission requested (which permission), rationale shown (yes/no), user response (granted/denied/never ask again), follow-up action taken.
- [ ] **9.3.10** `SharedPrefsSetupStateStore` — Log: step completion saves (which step, new value), reads (which step, cached value), clears (full clear vs single step), SharedPreferences commit success/failure.
- [ ] **9.3.11** `SetupViewModelFactory` — Log: factory construction with dependency list, ViewModel creation invocation.

#### Auth (5 files missing)
- [ ] **9.3.12** `OAuthFlowCoordinator` — Log: state machine state on EVERY transition: `Idle→Step1Loading→Step1Redirect→Step1Complete→Step2Loading→Step2Redirect→Complete` (or `→Error` at any point). Log URI parsing: raw URI, extracted params (redacted tokens), validation result. Log token handoff: from coordinator to credential store, confirmation of save. Log timeout: which step, how long waited, what was expected.
- [ ] **9.3.13** `GeckoAuthRuntime` — Log: GeckoView session creation (session ID), page load start/progress/complete for each URL, redirect interception (URL pattern matched, extracted params), navigation blocked/allowed decisions, GeckoView errors (category, description), process death detection.
- [ ] **9.3.14** `PkceHelper` — Log: verifier generation (length, entropy source), challenge computation (method: S256), code challenge length. Do NOT log the actual verifier or challenge values (security).
- [ ] **9.3.15** `OAuthUrls` — Log: URL construction for each endpoint (authorize, token, consent), parameter count, redirect URI used. Redact any token values in log output.

#### Presentation (4 files missing)
- [ ] **9.3.16** `DirectSurfaceProbeBackend` — Log: probe initiated (target Hz), activity launch intent, probe result received (actual Hz, surface dimensions), probe timeout, probe teardown.
- [ ] **9.3.17** `HybridX11PresentationBackend` — Log: backend selection criteria (probe result, user config, device capabilities), activity launch intent construction (all extras), X11 configuration (display, resolution, DPI).
- [ ] **9.3.18** `PresentationBackends` — Log: backend registration (name, class), selection query (criteria), selection result (which backend, why).

#### Presentation/Hybrid (2 files missing — CRITICAL for graphics debugging)
- [ ] **9.3.19** `X11AttachmentController` — This is where frames live or die. Wire the `logger` parameter to `AppLog` in `HybridX11HostActivity`. Log:
  - Every `tryAttach()` call: attempt number, binder state (alive/dead/null), fd state
  - Attachment success: fd value, latency from first attempt to success
  - Attachment failure: reason (binder dead, fd invalid, exception), will retry (yes/no), retry delay
  - Retry exhaustion: total attempts, total time spent, final state
  - X11 connection handoff: fd transferred, logcat fd transferred, LorieView.connect() called
  - Cancellation: who cancelled (lifecycle, user, error), cleanup actions taken
- [ ] **9.3.20** `LorieServiceConnector` — Log:
  - `attachXConnection()`: pre-call binder state, `getXConnection()` result (null?), `detachFd()` result (fd value, -1 = failure), `LorieView.connect(fd)` call, post-call state
  - `attachLogcat()`: `getLogcatOutput()` result, `detachFd()` result, `startLogcat(fd)` call, failure handling (swallowed vs propagated)
  - `sendWindowChange()`: dimensions sent, binder state at call time
  - Every method: wrap in timing — log latency for each binder call

#### Session (2 files missing)
- [ ] **9.3.21** `SessionNotificationHelper` — Log: notification channel creation (channel ID, importance), notification build (content title, action count), foreground service promotion (`startForeground` call), notification update, notification dismiss.
- [ ] **9.3.22** Log session state transitions IN `SessionHealthMonitor` and `RuneLiteSessionService`: every `SessionState` change with old→new, trigger event, timestamp. Log health poll results: sentinel file found (yes/no), consecutive STOPPED count, debounce state, transition decision.

#### DI (6 files missing)
- [ ] **9.3.23** `AppContainer` — Log: container creation start/end with timing, module instantiation order, total dependency count.
- [ ] **9.3.24** ALL DI modules (`AuthModule`, `InstallerModule`, `SessionModule`, `SetupModule`, `TermuxModule`) — Log lazy initialization on first access: which dependency, when (elapsed since app start), who triggered it (calling class if available via stack trace in debug).

#### Installer (1 file missing)
- [ ] **9.3.25** `InstallResultRegistry` — Log: deferred registered (execution ID), result arrived (execution ID, success/fail), deferred completed (execution ID, latency from registration to completion), orphaned result (arrived with no waiting deferred — indicates race or stale ID), cleanup (expired deferreds removed).

#### Termux (1 file missing)
- [ ] **9.3.26** `TermuxResultRegistry` — Same as InstallResultRegistry: register, arrive, complete, orphan, cleanup. Additionally log: timeout expiration (which execution ID, how long waited), pending count at each operation.

### 9.4 — Graphics/FPS Pipeline Logging (Exhaustive)

**This is the PRIMARY motivation for the entire logging system.** The rendering pipeline has 6 boundaries. Each boundary is a potential frame killer. We instrument EVERY handoff point, not just the obvious ones. The research identified specific line-level logging targets in both Kotlin and native C code.

#### Boundary A: Choreographer → Android App Layer
- [ ] **9.4.1** Wire `FrameTimingTracker` into `HybridX11HostActivity` via `Choreographer.FrameCallback.doFrame(frameTimeNanos)`.
- [ ] **9.4.2** Log every 120 frames (1 second at 120fps): `logger.frame(fps, jankCount, p99Ms, heapMb, "periodic")` — average FPS, jank count (>12ms), P99 frame time, current heap usage.
- [ ] **9.4.3** Log EVERY jank frame individually: `logger.jank(frameTimeMs, 8.33, frameNumber, "reason")` — frame time, expected time, frame number, suspected reason (GC? binder? surface lock?).
- [ ] **9.4.4** Log Choreographer re-registration: wrap `postFrameCallback(this)` in try-catch — if it throws, the entire frame loop is dead and nobody will know.
- [ ] **9.4.5** In `DirectSurfaceProbeActivity.doFrame()`: log `lockHardwareCanvas` failure (currently caught silently at line ~146) — this reveals GPU driver issues.
- [ ] **9.4.6** In `DirectSurfaceProbeActivity`: log `setFrameRate()` result — did the system honor our 120Hz request or silently ignore it?

#### Boundary B: LorieView Surface → Native X11 Bridge (JNI crossing)
- [ ] **9.4.7** `LorieView.surfaceCreated()`: Log `logger.surface("created", w, h, format, "holder=$holder")` with actual holder dimensions, not just measured.
- [ ] **9.4.8** `LorieView.surfaceChanged()`: Log measured size vs holder size — if they differ, rotation race is happening. Log: `logger.surface("changed", w, h, format, "measured=${size.x}x${size.y} holder=${holder.surfaceFrame}")`.
- [ ] **9.4.9** `LorieView.surfaceDestroyed()`: Log with timing — how long was the surface alive? Were frames being rendered?
- [ ] **9.4.10** Wrap `external fun surfaceChanged(surface: Surface?)` JNI call with timing: `val startNs = System.nanoTime(); surfaceChanged(surface); logger.ipc("send", "jni_surfaceChanged", (System.nanoTime()-startNs)/1_000_000, "surface=${surface!=null}")`. This measures JNI overhead.
- [ ] **9.4.11** `LorieView.triggerCallback()` (currently unlogged): Log the `post {}` and the callback execution — delayed execution here means UI thread is blocked.
- [ ] **9.4.12** Surface format negotiation: Log when `BGRA_8888` is requested vs what the system provides. Log `R8G8B8A8_UNORM` vs `R8G8B8X8_UNORM` selection — this is the root cause of the "Xlorie legacy drawing" blocker.

#### Boundary C: Native Renderer (C code — via Logcat bridge to endpoint)

These are `__android_log_print()` calls in C that the debug server captures via Logcat subscription.

- [ ] **9.4.13** `activity.c:nativeInit()` — Log JNI class/method lookup success/failure for EACH of the 16 registered methods. Currently partially logged; add per-method confirmation.
- [ ] **9.4.14** `activity.c:connect_()` — Log old fd → new fd transition: `"connect_: closing old fd=%d, adding new fd=%d to ALooper"`. Currently MISSING — fd leaks are invisible.
- [ ] **9.4.15** `activity.c:xcallback()` — Log event type on EVERY callback: `"xcallback: fd=%d events=%d type=%s"`. This is the X11 event loop — if it stops firing, frames stop. Add JNI exception check: `if ((*env)->ExceptionCheck(env)) { log(ERROR, "JNI exception in xcallback"); }`.
- [ ] **9.4.16** `activity.c:requestConnection()` — Log socket creation, connect attempt, poll result, SO_ERROR, MAGIC write: full lifecycle of X11 socket establishment. Currently partial — poll timeout is silent.
- [ ] **9.4.17** `activity.c:startLogcat()` — Log fork result, child exec, parent fd setup. Currently MISSING — fork failures are invisible.
- [ ] **9.4.18** `renderer.c:rendererInit()` — Log EGL display acquisition, context creation, shader compilation success/failure, program link result. Currently partial — add comprehensive init summary: `"rendererInit: display=%p context=%p shaders=%d/%d program=%d"`.
- [ ] **9.4.19** `renderer.c:checkGlError()` — Fix: currently returns after first error, hiding subsequent errors. Change to drain ALL errors: `do { err = glGetError(); log(...); } while (err != GL_NO_ERROR);`.
- [ ] **9.4.20** `renderer.c` perf stats — Currently logs every 5 seconds. Change to every 1 second (120 frames). Add: lock time max, fence wait max, swap time max, frame count, buffer format, drawing mode (legacy vs DMA vs SHM).
- [ ] **9.4.21** `renderer.c` shader compilation — Currently MISSING individual shader compile logs. Add after each `glCompileShader()`: `"shader compiled: type=%s status=%d log='%s'"`.

#### Boundary D: Binder Bridge (HybridX11Bridge + TermuxX11StartReceiver)
- [ ] **9.4.22** `HybridX11Bridge.attach()`: Log binder identity (hashCode), alive state, same-binder check, linkToDeath registration success, generation increment. Use `logger.ipc()`.
- [ ] **9.4.23** `HybridX11Bridge.clear()`: Log reason, was-alive state, generation at clear time.
- [ ] **9.4.24** `TermuxX11StartReceiver.onReceive()`: Log broadcast received, bundle extraction, binder extraction, alive check (BOTH checks — the race between line 30 and 40 is a known risk), stub cast result, attach call.
- [ ] **9.4.25** Binder DeathRecipient: Log death detection with timing — how long was the binder alive? What was the last successful operation? This reveals binder churn patterns.
- [ ] **9.4.26** Track and log broadcast count per second — frame throughput estimation. If broadcasts stop, X11 server died.

#### Boundary E: File Descriptor Lifecycle (X11 socket + Binder fds)
- [ ] **9.4.27** `LorieServiceConnector.attachXConnection()`: Log EVERY fd operation: `getXConnection()` result, `detachFd()` value, `LorieView.connect(fd)` call. If fd < 0, log as ERROR with binder state.
- [ ] **9.4.28** `LorieServiceConnector.attachLogcat()`: Log fd detach, `startLogcat(fd)` call. Currently failure is swallowed ("don't block on logcat") — at minimum log the swallow.
- [ ] **9.4.29** `activity.c:connect_()` fd close: Log `close(conn_fd)` result — if it fails, fd leak. Log new fd assignment.
- [ ] **9.4.30** Create fd tracking map in debug builds: register every fd open/close, log at `onPause()` to detect leaks. Pattern: `logger.fd("open", fd, path, "purpose")` / `logger.fd("close", fd, path, "cleanup")`.

#### Boundary F: Buffer/Memory Operations (Native shared memory)
- [ ] **9.4.31** `activity.c` EVENT_SHARED_SERVER_STATE: Log mmap result (success/fail), mapped size, fd closure. Currently partial — enhance with: `"mmap: addr=%p size=%zu fd=%d"`.
- [ ] **9.4.32** `activity.c` LorieBuffer_recvHandleFromUnixSocket: Log buffer receipt, buffer descriptor, allocation success/failure. Currently partial — add null check log.
- [ ] **9.4.33** `activity.c` clipboard operations: Log buffer allocation, charset decode, JNI ByteBuffer creation — any of these can fail silently.
- [ ] **9.4.34** Track buffer allocation/deallocation balance in debug builds — log periodic summary of outstanding allocations.

#### Boundary G: Thread Transitions in Graphics Path (NEW — not in original 6)
- [ ] **9.4.35** Log EVERY thread switch in the frame path: `logger.thread("ALooper", "UI", "xcallback_jni_upcall", "calling clientConnectedStateChanged")`. Thread switches are where race conditions hide.
- [ ] **9.4.36** `HybridX11HostActivity.lifecycleScope.launch {}`: Log coroutine start thread and actual execution thread — if they differ, dispatcher routing is wrong.
- [ ] **9.4.37** `X11AttachmentController` retry loop: Log which thread each retry executes on — thread pool exhaustion causes retry stalls.
- [ ] **9.4.38** `LorieView.post {}` calls: Log post time vs execution time — long delays mean UI thread is saturated.
- [ ] **9.4.39** Native `AttachCurrentThread` in `activity.c`: Log success/failure — JVM attachment from native threads can fail under memory pressure.

#### Boundary H: Process Lifecycle (RuneLite + VirGL + Termux) (NEW)
- [ ] **9.4.40** `HybridX11TestReceiver`: Log X11 process kill (which PIDs), sleep duration, restart confirmation. Log script validation BEFORE execution — is the script file present and executable?
- [ ] **9.4.41** VirGL server process: Log start, health check (is process alive?), stderr output, unexpected death, restart. VirGL death = no GPU = black screen.
- [ ] **9.4.42** RuneLite Java process: Log launch command, PID (if capturable), stdout first 1KB, crash detection (exit code != 0), OOM kill detection.
- [ ] **9.4.43** Launch script execution: Log every env var set (redact tokens), every path validated, every prerequisite check, GPU driver selection rationale, final exec command.

### 9.5 — Correlation IDs for Cross-Boundary Tracing

Without correlation, a log stream at 120fps is noise. Correlation makes it a story.

- [ ] **9.5.1** Generate UUID-based `correlationId` at each top-level user action: setup step start, auth flow start, launch start, session start. Format: `{action}-{shortUuid}` (e.g., `launch-a3f7`, `auth-b2c1`).
- [ ] **9.5.2** Thread `correlationId` as parameter through EVERY method call in the operation's chain. This means method signatures change — every coordinator, service, and manager method gains an optional `correlationId` parameter.
- [ ] **9.5.3** For frame-level correlation: use a `frameSessionId` generated at rendering start that persists across all frame logs for that session. Separate from per-action correlationId.
- [ ] **9.5.4** For Termux commands: the existing `executionId` (AtomicInteger) becomes the correlationId for that command's lifecycle — from intent send through result receive.
- [ ] **9.5.5** Debug server viewer: filter by correlationId to see COMPLETE operation trace across Kotlin → JNI → native C → shell. Timeline view shows events in chronological order with source indicators.
- [ ] **9.5.6** Nested correlation: when one operation triggers another (e.g., launch triggers auth refresh), log BOTH correlationIds: `parentCorrelationId` + `correlationId`.

### 9.6 — Things You Might Not Have Thought Of

Additional logging points discovered during research that aren't covered by the boundary model:

- [ ] **9.6.1** **SharedPreferences listener race**: `HybridX11HostActivity` registers an `OnSharedPreferenceChangeListener` — if the callback fires during `onDestroy()` (before `lorieView` is initialized), it crashes silently. Log: guard check + listener registration/deregistration.
- [ ] **9.6.2** **Choreographer postFrameCallback exception**: If `doFrame()` throws during frame processing, `postFrameCallback(this)` for the NEXT frame never gets called. The frame loop silently dies. Nobody ever logs this. Wrap the re-registration in try-catch with error log.
- [ ] **9.6.3** **LogFileWriter queue growth**: The `ConcurrentLinkedQueue` is unbounded. Under high-frequency frame logging (120 lines/sec), if the flush thread stalls, memory grows indefinitely. Add periodic log of queue size and warn if > 1000 entries.
- [ ] **9.6.4** **Handler thread death**: `LogFileWriter` uses a `HandlerThread`. If that thread dies (OOM, unhandled exception), ALL file logging stops silently. Log handler thread health on each flush.
- [ ] **9.6.5** **ALooper event error/hangup**: In `xcallback()`, `ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP` means the X11 connection is dead. Currently logged at disconnect but not the error/hangup flags. Log both.
- [ ] **9.6.6** **JNI exception leak**: Every `(*env)->Call*Method()` in `activity.c` can set a JNI exception. If not checked and cleared, subsequent JNI calls crash. Add `ExceptionCheck()` after every JNI upcall in `xcallback()`.
- [ ] **9.6.7** **GL error drain**: `renderer.c:checkGlError()` returns after first error. Multiple errors can stack — subsequent errors are hidden. Fix to drain all errors.
- [ ] **9.6.8** **Surface hardware canvas fallback**: `DirectSurfaceProbeActivity.drawFrame()` catches `lockHardwareCanvas()` failure and falls back to `lockCanvas()`. This means software rendering is active — log this as a WARNING because it means GPU acceleration failed.
- [ ] **9.6.9** **Binder alive double-check race**: `TermuxX11StartReceiver` checks `binder.isBinderAlive` at line ~30, then uses the binder at line ~44. The binder can die between these lines. Log the check result AND the use result independently.
- [ ] **9.6.10** **Memory pressure correlation**: When `PerfSnapshots.memorySnapshot()` shows high heap usage AND frame times spike, log a composite event: `logger.perf("MEMORY_PRESSURE: heap=${heapMb}MB free=${freeMb}MB concurrent_jank=${jankCount}")`. This catches GC-induced jank.
- [ ] **9.6.11** **Separate perf log file**: Frame logs at 120Hz = 120 lines/sec = 7200 lines/min. Mixing with general app logs makes both unreadable. Create a separate `PerfLogWriter` for frame-related logs, same async pattern, separate file (`rlt-perf-*.log`), separate rotation. Both streams still flow to the single WebSocket endpoint.

### 9.7 — Remaining Spec Items Still Incomplete

#### Device Verification (P0)
- [ ] **9.7.1** Build and deploy to Samsung Tab S10 Ultra
- [ ] **9.7.2** Verify app boots to setup screen
- [ ] **9.7.3** Verify setup flow completes (Termux, proot, Java, RuneLite, X11)
- [ ] **9.7.4** Verify auth flow works (Jagex OAuth 2-step)
- [ ] **9.7.5** Verify RuneLite launches and renders frames
- [ ] **9.7.6** Verify session health monitoring works

#### Phase 5: MockWebServer Integration Tests (P1)
- [ ] **9.7.7** `JagexOAuth2ManagerTest` — real manager + real OkHttp + MockWebServer (spec item 5.1)
- [ ] **9.7.8** `ApkDownloaderTest` — real downloader + real OkHttp + MockWebServer (spec item 5.2)
- [ ] **9.7.9** `LaunchEnvDeployerTest` already exists — verify coverage is complete (spec item 5.3)

#### Phase 4: Missing Tests (P2)
- [ ] **9.7.10** `LaunchCoordinatorTest` — pre-launch sequence testing (spec item 4.9)
- [ ] **9.7.11** `SetupViewModelTest` — all state transition scenarios (spec item 4.1)

#### AppLog 50-Line Target
- [ ] **9.7.12** Trim `AppLog` from 58 LoC to ≤50 LoC (move `memorySnapshot`/`diskSnapshot`/`perfSnapshot` convenience methods elsewhere)

### 9.8 — Verification Gate

**The logging system is not done until ALL of these are true:**

- [ ] EVERY file in `main/java/com/runelitetablet/` that contains business logic or data handoff has at least one `logger.*` call — **zero exceptions**
- [ ] `DebugLogServer` starts on DEBUG builds and streams structured JSON to WebSocket on port 8099
- [ ] Native C logs (`activity.c`, `renderer.c`) flow through Logcat bridge into the same WebSocket stream with `"source": "native"`
- [ ] Shell script logs flow through `TermuxCommandRunner` into the same stream with `"source": "shell"`
- [ ] `adb forward tcp:8099 tcp:8099` + browser at `http://localhost:8099/` shows live filterable log stream
- [ ] Systematic-debug skill can connect to `ws://localhost:8099/ws` and receive the JSON stream
- [ ] FPS metrics logged every second during active rendering (aggregate + individual janks)
- [ ] All 8 graphics pipeline boundaries (A through H) have explicit handoff logging at EVERY crossing point
- [ ] `FrameTimingTracker` wired into `HybridX11HostActivity` via Choreographer
- [ ] Correlation IDs trace a full operation from user tap through Kotlin → JNI → native → shell → back
- [ ] Separate perf log file for frame-rate data (doesn't pollute general logs)
- [ ] fd tracking active in debug builds — open/close/transfer/leak detection
- [ ] JNI exception checks after every native upcall in `xcallback()`
- [ ] GL error drain (not single-error return) in `renderer.c`
- [ ] Log queue health monitoring in `LogFileWriter` (warn on >1000 pending)
- [ ] No silent catch blocks anywhere in the graphics path — every catch logs

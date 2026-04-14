package com.runelitetablet.presentation.setup

import com.runelitetablet.auth.AuthResult
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.setup.AppScreen
import com.runelitetablet.setup.AuthCoordinator
import com.runelitetablet.setup.MarkerReconciler
import com.runelitetablet.setup.SetupOrchestrator
import com.runelitetablet.setup.SetupStepRunner
import com.runelitetablet.setup.SetupVerifier
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.testutil.FakeCleaner
import com.runelitetablet.testutil.FakeCommandRunner
import com.runelitetablet.testutil.FakeCredentialStore
import com.runelitetablet.testutil.FakePackageChecker
import com.runelitetablet.testutil.FakePermissionChecker
import com.runelitetablet.testutil.FakeScriptDeployer
import com.runelitetablet.testutil.FakeSetupStateStore
import com.runelitetablet.testutil.MainDispatcherRule
import com.runelitetablet.testutil.PrintLogger
import com.runelitetablet.testutil.UnsafeHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthCoordinatorTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var coordinator: AuthCoordinator
    private var lastScreen: AppScreen? = null
    private var performLaunchCalled = false

    @Before
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        credentialStore = FakeCredentialStore()
        val commandRunner = FakeCommandRunner()
        val logger = PrintLogger()

        val orchestrator = SetupOrchestrator(
            packageChecker = FakePackageChecker(),
            stepRunner = SetupStepRunner(commandRunner, FakePackageChecker(),
                UnsafeHelper.allocate(ApkDownloader::class.java),
                UnsafeHelper.allocate(ApkInstaller::class.java),
                FakeScriptDeployer(), FakeSetupStateStore(), logger),
            verifier = SetupVerifier(commandRunner, logger),
            reconciler = MarkerReconciler(commandRunner, FakeScriptDeployer(), logger),
            cleaner = FakeCleaner(),
            stateStore = FakeSetupStateStore(),
            permissionChecker = FakePermissionChecker(),
            apkInstaller = UnsafeHelper.allocate(ApkInstaller::class.java),
            ioDispatcher = testDispatcher, logger = logger
        )

        lastScreen = null; performLaunchCalled = false
        coordinator = AuthCoordinator(
            context = UnsafeHelper.allocate(android.app.Application::class.java),
            credentialStore = credentialStore,
            oAuth2Manager = UnsafeHelper.allocate(JagexOAuth2Manager::class.java),
            orchestrator = orchestrator,
            scope = TestScope(testDispatcher),
            onScreenChange = { lastScreen = it },
            onPerformLaunch = { performLaunchCalled = true },
            logger = logger, ioDispatcher = testDispatcher
        )
    }

    @Test fun `getDisplayName returns null when no credentials`() = assertNull(coordinator.getDisplayName())

    @Test fun `getDisplayName returns stored name`() {
        credentialStore.storeGameSession("sid", "cid", "TestPlayer")
        assertEquals("TestPlayer", coordinator.getDisplayName())
    }

    @Test fun `clearState resets pending flags`() {
        coordinator.pendingLaunchAfterAuth.set(true)
        coordinator.clearState()
        assertFalse(coordinator.pendingLaunchAfterAuth.get())
    }

    @Test fun `skipLogin navigates to Launch`() {
        coordinator.skipLogin()
        assertEquals(AppScreen.Launch, lastScreen)
    }

    @Test fun `signOut clears credentials and shows Login`() {
        credentialStore.storeTokens("t", "r", 999)
        credentialStore.storeGameSession("s", "c", "P")
        coordinator.signOut()
        assertFalse(credentialStore.hasCredentials())
        assertEquals(AppScreen.Login, lastScreen)
    }

    @Test fun `navigateToLaunchOrResume goes to Launch when no pending`() = runTest {
        coordinator.navigateToLaunchOrResume()
        assertEquals(AppScreen.Launch, lastScreen)
        assertFalse(performLaunchCalled)
    }

    @Test fun `navigateToLaunchOrResume calls performLaunch when pending`() = runTest {
        coordinator.pendingLaunchAfterAuth.set(true)
        coordinator.navigateToLaunchOrResume()
        assertTrue(performLaunchCalled)
        assertFalse(coordinator.pendingLaunchAfterAuth.get())
    }

    @Test fun `refreshIfNeeded returns Valid when token not expired`() = runTest {
        credentialStore.storeTokens("tok", "ref", System.currentTimeMillis() / 1000 + 3600)
        assertEquals(AuthResult.Valid, coordinator.refreshIfNeeded())
    }

    @Test fun `refreshIfNeeded returns NeedsLogin when no refresh token`() = runTest {
        credentialStore.storeTokens("tok", null, 0)
        assertEquals(AuthResult.NeedsLogin, coordinator.refreshIfNeeded())
    }

    @Test fun `startLogin with no actions shows error`() {
        coordinator.startLogin()
        assertTrue(lastScreen is AppScreen.AuthError)
    }
}

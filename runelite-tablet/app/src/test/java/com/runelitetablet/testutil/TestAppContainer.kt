package com.runelitetablet.testutil

/**
 * Test DI container mirroring production AppContainer.
 * Drop-in replacement with all Android-boundary deps faked.
 */
class TestAppContainer {
    val logger = PrintLogger()
    val commandRunner = FakeCommandRunner()
    val packageChecker = FakePackageChecker()
    val credentialStore = FakeCredentialStore()
    val setupStateStore = FakeSetupStateStore()
    val scriptDeployer = FakeScriptDeployer()
    val cleaner = FakeCleaner()
}

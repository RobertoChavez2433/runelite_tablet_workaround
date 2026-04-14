package com.runelitetablet.data.auth

import com.runelitetablet.auth.LaunchEnvDeployer
import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.testutil.FakeCommandRunner
import com.runelitetablet.testutil.FakeCredentialStore
import com.runelitetablet.testutil.PrintLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LaunchEnvDeployerTest {

    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var commandRunner: FakeCommandRunner
    private lateinit var logger: PrintLogger

    @Before
    fun setup() {
        credentialStore = FakeCredentialStore()
        commandRunner = FakeCommandRunner()
        logger = PrintLogger()
    }

    private fun storeTestCredentials(
        sessionId: String = "sess-123",
        characterId: String = "char-456",
        displayName: String = "TestPlayer"
    ) {
        credentialStore.storeGameSession(sessionId, characterId, displayName)
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    fun `returns env file path when credentials present and command succeeds`() = runTest {
        storeTestCredentials()
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        val result = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertNotNull(result)
        assertEquals("${CommandRunner.TERMUX_HOME_PATH}/.rlt-launch-env.sh", result)
    }

    @Test
    fun `stdin content contains all credential env vars`() = runTest {
        storeTestCredentials(
            sessionId = "my-session",
            characterId = "my-char",
            displayName = "Player One"
        )
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertEquals(1, commandRunner.executedCommands.size)
        val stdin = commandRunner.executedCommands[0].stdin!!
        assertTrue(stdin.contains("export JX_SESSION_ID=\"my-session\""))
        assertTrue(stdin.contains("export JX_CHARACTER_ID=\"my-char\""))
        assertTrue(stdin.contains("export JX_DISPLAY_NAME=\"Player One\""))
    }

    @Test
    fun `uses bash with cat redirect command`() = runTest {
        storeTestCredentials()
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        val cmd = commandRunner.executedCommands[0]
        assertEquals("${CommandRunner.TERMUX_BIN_PATH}/bash", cmd.commandPath)
        assertNotNull(cmd.arguments)
        assertTrue(cmd.arguments!!.any { it.contains("cat >") && it.contains("chmod 600") })
    }

    @Test
    fun `executes in background mode`() = runTest {
        storeTestCredentials()
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertTrue(commandRunner.executedCommands[0].background)
    }

    // ── No credentials ──────────────────────────────────────────────────

    @Test
    fun `returns null when no credentials stored`() = runTest {
        val result = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertNull(result)
        assertEquals(0, commandRunner.executedCommands.size)
    }

    @Test
    fun `returns null after credentials cleared`() = runTest {
        storeTestCredentials()
        credentialStore.clearCredentials()

        val result = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertNull(result)
    }

    // ── Command failure ─────────────────────────────────────────────────

    @Test
    fun `returns null when command fails`() = runTest {
        storeTestCredentials()
        commandRunner.setDefaultResult(CommandResult(
            stdout = null, stderr = "permission denied", exitCode = 1, error = null
        ))

        val result = LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertNull(result)
    }

    // ── Shell escaping ──────────────────────────────────────────────────

    @Test
    fun `shell-escapes double quotes in credentials`() = runTest {
        storeTestCredentials(displayName = "Player \"The Best\"")
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        val stdin = commandRunner.executedCommands[0].stdin!!
        assertTrue(stdin.contains("Player \\\"The Best\\\""))
    }

    @Test
    fun `shell-escapes dollar signs in credentials`() = runTest {
        storeTestCredentials(sessionId = "sess\$var")
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        val stdin = commandRunner.executedCommands[0].stdin!!
        assertTrue(stdin.contains("sess\\\$var"))
    }

    @Test
    fun `shell-escapes backslashes in credentials`() = runTest {
        storeTestCredentials(characterId = "char\\id")
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        val stdin = commandRunner.executedCommands[0].stdin!!
        assertTrue(stdin.contains("char\\\\id"))
    }

    @Test
    fun `shell-escapes backticks in credentials`() = runTest {
        storeTestCredentials(displayName = "Player `cmd`")
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        val stdin = commandRunner.executedCommands[0].stdin!!
        assertTrue(stdin.contains("Player \\`cmd\\`"))
    }

    @Test
    fun `uses correct timeout`() = runTest {
        storeTestCredentials()
        commandRunner.setDefaultResult(CommandResult(
            stdout = "", stderr = null, exitCode = 0, error = null
        ))

        LaunchEnvDeployer.deployToTermuxHome(credentialStore, commandRunner, logger)

        assertEquals(CommandRunner.TIMEOUT_VERIFY_MS, commandRunner.executedCommands[0].timeoutMs)
    }
}

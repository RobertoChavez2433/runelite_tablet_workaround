package com.runelitetablet.data.session

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.session.SessionHealthMonitor
import com.runelitetablet.session.SessionState
import com.runelitetablet.testutil.FakeCommandRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionHealthMonitorTest {

    private lateinit var fakeRunner: FakeCommandRunner
    private lateinit var testScope: TestScope
    private lateinit var monitor: SessionHealthMonitor

    private val bashPath = "${CommandRunner.TERMUX_BIN_PATH}/bash"

    @Before
    fun setUp() {
        fakeRunner = FakeCommandRunner()
        testScope = TestScope(UnconfinedTestDispatcher())
        monitor = SessionHealthMonitor(fakeRunner, testScope)
    }

    private fun configureHealthResponse(stdout: String) {
        fakeRunner.whenCommand(
            bashPath,
            CommandResult(stdout = stdout, stderr = null, exitCode = 0, error = null)
        )
    }

    // --- Single health check tests ---

    @Test
    fun `checkHealth returns Running when sentinel file exists`() = runTest {
        configureHealthResponse("RUNNING")
        val state = monitor.checkHealth()
        assertEquals(SessionState.Running, state)
    }

    @Test
    fun `checkHealth returns Stopped when sentinel file absent`() = runTest {
        configureHealthResponse("STOPPED")
        val state = monitor.checkHealth()
        assertEquals(SessionState.Stopped, state)
    }

    @Test
    fun `checkHealth returns Error on unexpected output`() = runTest {
        configureHealthResponse("SOMETHING_ELSE")
        val state = monitor.checkHealth()
        assertTrue("Expected SessionState.Error, got $state", state is SessionState.Error)
        assertEquals("Health check: unexpected output", (state as SessionState.Error).message)
    }

    @Test
    fun `checkHealth returns Error on null stdout`() = runTest {
        fakeRunner.whenCommand(
            bashPath,
            CommandResult(stdout = null, stderr = null, exitCode = -1, error = "timeout")
        )
        val state = monitor.checkHealth()
        assertTrue("Expected SessionState.Error, got $state", state is SessionState.Error)
    }

    // --- Debounce tests via polling ---

    @Test
    fun `debounce requires 3 consecutive STOPPED before transitioning`() = testScope.runTest {
        configureHealthResponse("STOPPED")
        val emittedStates = mutableListOf<SessionState>()

        val job = monitor.startPolling { emittedStates.add(it) }

        // First poll fires immediately (UnconfinedTestDispatcher), debounce suppresses STOPPED (count=1)
        // Advance through 2 more poll intervals (5_000ms each for non-running state)
        advanceTimeBy(5_001L) // 2nd poll -> count=2, still suppressed
        advanceTimeBy(5_001L) // 3rd poll -> count=3, emitted

        job.cancel()

        assertEquals(1, emittedStates.size)
        assertEquals(SessionState.Stopped, emittedStates[0])
    }

    @Test
    fun `debounce emits Running immediately without debounce`() = testScope.runTest {
        configureHealthResponse("RUNNING")
        val emittedStates = mutableListOf<SessionState>()

        val job = monitor.startPolling { emittedStates.add(it) }

        // Running is emitted immediately on first poll
        job.cancel()

        assertTrue("Expected at least 1 emitted state", emittedStates.isNotEmpty())
        assertEquals(SessionState.Running, emittedStates[0])
    }

    @Test
    fun `debounce stopped count resets on Running`() = testScope.runTest {
        // Start with STOPPED to accumulate count
        configureHealthResponse("STOPPED")
        val emittedStates = mutableListOf<SessionState>()

        val job = monitor.startPolling { emittedStates.add(it) }

        // 1st poll: STOPPED (count=1, suppressed)
        advanceTimeBy(5_001L) // 2nd poll: STOPPED (count=2, suppressed)

        // Switch to RUNNING — emits immediately and resets counters
        configureHealthResponse("RUNNING")
        advanceTimeBy(5_001L) // 3rd poll: RUNNING (emitted, counters reset)

        // Switch back to STOPPED — needs 3 fresh consecutive
        configureHealthResponse("STOPPED")
        advanceTimeBy(60_001L) // 4th poll: STOPPED (count=1, suppressed — 60s interval after RUNNING)
        advanceTimeBy(5_001L)  // 5th poll: STOPPED (count=2, suppressed)

        job.cancel()

        // Only RUNNING should have been emitted
        assertEquals(1, emittedStates.size)
        assertEquals(SessionState.Running, emittedStates[0])
    }

    @Test
    fun `stopPolling resets counters`() = testScope.runTest {
        configureHealthResponse("STOPPED")
        val firstStates = mutableListOf<SessionState>()
        val job1 = monitor.startPolling { firstStates.add(it) }
        advanceTimeBy(5_001L) // 2nd poll: count=2
        job1.cancel()

        monitor.stopPolling()

        // New polling — needs 3 fresh STOPPED readings
        val secondStates = mutableListOf<SessionState>()
        val job2 = monitor.startPolling { secondStates.add(it) }
        // 1st poll: count=1 (suppressed)
        advanceTimeBy(5_001L) // 2nd poll: count=2 (suppressed)
        assertTrue("Expected no STOPPED after reset, got $secondStates", secondStates.isEmpty())

        advanceTimeBy(5_001L) // 3rd poll: count=3 (emitted)
        assertEquals(1, secondStates.size)
        assertEquals(SessionState.Stopped, secondStates[0])

        job2.cancel()
    }

    @Test
    fun `error debounce requires 3 consecutive errors before emitting`() = testScope.runTest {
        configureHealthResponse("GARBAGE")
        val emittedStates = mutableListOf<SessionState>()

        val job = monitor.startPolling { emittedStates.add(it) }

        // 1st poll: Error (count=1, suppressed)
        advanceTimeBy(5_001L) // 2nd poll: Error (count=2, suppressed)
        assertTrue("Expected no emissions yet", emittedStates.isEmpty())

        advanceTimeBy(5_001L) // 3rd poll: Error (count=3, emitted)
        assertEquals(1, emittedStates.size)
        assertTrue(emittedStates[0] is SessionState.Error)

        job.cancel()
    }

    @Test
    fun `checkHealth sends correct command path and arguments`() = runTest {
        configureHealthResponse("RUNNING")
        monitor.checkHealth()

        assertEquals(1, fakeRunner.executedCommands.size)
        val cmd = fakeRunner.executedCommands[0]
        assertEquals(bashPath, cmd.commandPath)
        assertTrue("Expected background=true", cmd.background)
        assertTrue("Expected -c argument", cmd.arguments?.contains("-c") == true)
    }
}

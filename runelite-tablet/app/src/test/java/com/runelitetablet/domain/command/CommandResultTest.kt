package com.runelitetablet.domain.command

import org.junit.Assert.*
import org.junit.Test

class CommandResultTest {

    @Test
    fun `isSuccess when exitCode is 0 and no error`() {
        val result = CommandResult(stdout = "ok", stderr = null, exitCode = 0, error = null)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `not success when exitCode is non-zero`() {
        val result = CommandResult(stdout = null, stderr = "fail", exitCode = 1, error = null)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `not success when error is present`() {
        val result = CommandResult(stdout = null, stderr = null, exitCode = 0, error = "timeout")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `not success when both exitCode and error`() {
        val result = CommandResult(stdout = null, stderr = "bad", exitCode = 1, error = "err")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `success with empty stdout`() {
        val result = CommandResult(stdout = "", stderr = null, exitCode = 0, error = null)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `success with null stdout`() {
        val result = CommandResult(stdout = null, stderr = null, exitCode = 0, error = null)
        assertTrue(result.isSuccess)
    }
}

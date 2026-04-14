package com.runelitetablet.data.setup

import com.runelitetablet.setup.MarkerReconciler
import org.junit.Assert.*
import org.junit.Test

class MarkerReconcilerTest {

    @Test
    fun `parseOutput with all PRESENT markers`() {
        val result = MarkerReconciler.parseOutput(
            "VERSION 7\nPRESENT step-proot\nPRESENT step-java\nPRESENT step-runelite\nPRESENT step-gpu"
        )
        assertEquals(setOf("step-proot", "step-java", "step-runelite", "step-gpu"), result.presentKeys)
        assertTrue(result.absentKeys.isEmpty())
        assertFalse(result.versionMismatch)
    }

    @Test
    fun `parseOutput with ABSENT markers`() {
        val result = MarkerReconciler.parseOutput(
            "VERSION 7\nPRESENT step-proot\nABSENT step-java\nABSENT step-runelite\nPRESENT step-gpu"
        )
        assertEquals(setOf("step-proot", "step-gpu"), result.presentKeys)
        assertEquals(setOf("step-java", "step-runelite"), result.absentKeys)
    }

    @Test
    fun `parseOutput detects version mismatch`() {
        val result = MarkerReconciler.parseOutput("VERSION 5\nPRESENT step-proot")
        assertTrue(result.versionMismatch)
    }

    @Test
    fun `parseOutput with matching version`() {
        val result = MarkerReconciler.parseOutput("VERSION 7\nPRESENT step-proot")
        assertFalse(result.versionMismatch)
    }

    @Test
    fun `parseOutput with empty output`() {
        val result = MarkerReconciler.parseOutput("")
        assertTrue(result.presentKeys.isEmpty())
        assertTrue(result.absentKeys.isEmpty())
        assertFalse(result.versionMismatch)
    }

    @Test
    fun `parseOutput ignores unknown lines`() {
        val result = MarkerReconciler.parseOutput(
            "VERSION 7\nSOMETHING random\nPRESENT step-proot\nGARBAGE"
        )
        assertEquals(setOf("step-proot"), result.presentKeys)
        assertTrue(result.absentKeys.isEmpty())
    }

    @Test
    fun `parseOutput with VERSION none is not a mismatch`() {
        val result = MarkerReconciler.parseOutput("VERSION none\nPRESENT step-proot")
        assertFalse(result.versionMismatch)
    }

    @Test
    fun `parseOutput trims whitespace from keys`() {
        val result = MarkerReconciler.parseOutput("PRESENT  step-proot \nABSENT  step-java ")
        assertEquals(setOf("step-proot"), result.presentKeys)
        assertEquals(setOf("step-java"), result.absentKeys)
    }

    @Test
    fun `parseOutput with no VERSION line`() {
        val result = MarkerReconciler.parseOutput("PRESENT step-proot\nABSENT step-java")
        assertFalse(result.versionMismatch)
        assertEquals(setOf("step-proot"), result.presentKeys)
        assertEquals(setOf("step-java"), result.absentKeys)
    }
}

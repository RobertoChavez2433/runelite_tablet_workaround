package com.runelitetablet.testutil

import com.runelitetablet.domain.setup.SetupStateStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeSetupStateStoreTest {
    private lateinit var store: FakeSetupStateStore

    @Before
    fun setup() {
        store = FakeSetupStateStore()
    }

    @Test
    fun `initially no completed steps`() {
        assertFalse(store.isCompleted("any_step"))
    }

    @Test
    fun `markCompleted and isCompleted round-trip`() {
        store.markCompleted("step_a")
        assertTrue(store.isCompleted("step_a"))
        assertFalse(store.isCompleted("step_b"))
    }

    @Test
    fun `clearCompleted removes step`() {
        store.markCompleted("step_a")
        store.clearCompleted("step_a")
        assertFalse(store.isCompleted("step_a"))
    }

    @Test
    fun `clearCompleted on unknown key is no-op`() {
        store.clearCompleted("nonexistent")
        assertFalse(store.isCompleted("nonexistent"))
    }

    @Test
    fun `getStoredVersion defaults to zero`() {
        assertEquals("0", store.getStoredVersion())
    }

    @Test
    fun `setStoredVersion and getStoredVersion round-trip`() {
        store.setStoredVersion("5")
        assertEquals("5", store.getStoredVersion())
    }

    @Test
    fun `isVersionCurrent true when matching`() {
        store.setStoredVersion(SetupStateStore.CURRENT_SCRIPT_VERSION)
        assertTrue(store.isVersionCurrent())
    }

    @Test
    fun `isVersionCurrent false when not matching`() {
        store.setStoredVersion("0")
        assertFalse(store.isVersionCurrent())
    }

    @Test
    fun `clearAll resets completed steps and version`() {
        store.markCompleted("step_a")
        store.markCompleted("step_b")
        store.setStoredVersion("5")
        store.clearAll()

        assertFalse(store.isCompleted("step_a"))
        assertFalse(store.isCompleted("step_b"))
        assertEquals("0", store.getStoredVersion())
    }

    @Test
    fun `implements SetupStateStore interface`() {
        val polymorphic: SetupStateStore = store
        polymorphic.markCompleted("via_interface")
        assertTrue(polymorphic.isCompleted("via_interface"))
    }
}

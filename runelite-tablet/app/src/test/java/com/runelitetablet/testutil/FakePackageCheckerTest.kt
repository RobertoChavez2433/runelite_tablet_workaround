package com.runelitetablet.testutil

import com.runelitetablet.domain.installer.PackageChecker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakePackageCheckerTest {
    private lateinit var checker: FakePackageChecker

    @Before
    fun setup() {
        checker = FakePackageChecker()
    }

    @Test
    fun `initially no packages installed`() {
        assertFalse(checker.isPackageInstalled("com.example"))
        assertNull(checker.getVersionCode("com.example"))
    }

    @Test
    fun `setInstalled makes package detectable`() {
        checker.setInstalled("com.termux", 118L)
        assertTrue(checker.isPackageInstalled("com.termux"))
        assertEquals(118L, checker.getVersionCode("com.termux"))
    }

    @Test
    fun `setNotInstalled removes package`() {
        checker.setInstalled("com.termux", 100L)
        checker.setNotInstalled("com.termux")
        assertFalse(checker.isPackageInstalled("com.termux"))
        assertNull(checker.getVersionCode("com.termux"))
    }

    @Test
    fun `isTermuxInstalled delegates to isPackageInstalled`() {
        assertFalse(checker.isTermuxInstalled())
        checker.setInstalled(PackageChecker.TERMUX_PACKAGE, 1L)
        assertTrue(checker.isTermuxInstalled())
    }

    @Test
    fun `isTermuxX11Installed delegates to isPackageInstalled`() {
        assertFalse(checker.isTermuxX11Installed())
        checker.setInstalled(PackageChecker.TERMUX_X11_PACKAGE, 1L)
        assertTrue(checker.isTermuxX11Installed())
    }

    @Test
    fun `getTermuxVersionCode returns version`() {
        assertNull(checker.getTermuxVersionCode())
        checker.setInstalled(PackageChecker.TERMUX_PACKAGE, 42L)
        assertEquals(42L, checker.getTermuxVersionCode())
    }

    @Test
    fun `multiple packages tracked independently`() {
        checker.setInstalled("com.a", 1L)
        checker.setInstalled("com.b", 2L)
        assertTrue(checker.isPackageInstalled("com.a"))
        assertTrue(checker.isPackageInstalled("com.b"))
        assertEquals(1L, checker.getVersionCode("com.a"))
        assertEquals(2L, checker.getVersionCode("com.b"))
    }
}

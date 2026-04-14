package com.runelitetablet.domain.installer

/**
 * Package installation checker. Pure Kotlin — no Android imports.
 * Production: TermuxPackageHelper (queries Android PackageManager).
 * Tests: FakePackageChecker (configurable map).
 */
interface PackageChecker {
    fun isPackageInstalled(packageName: String): Boolean
    fun getVersionCode(packageName: String): Long?

    fun isTermuxInstalled(): Boolean = isPackageInstalled(TERMUX_PACKAGE)
    fun isTermuxX11Installed(): Boolean = isPackageInstalled(TERMUX_X11_PACKAGE)
    fun getTermuxVersionCode(): Long? = getVersionCode(TERMUX_PACKAGE)
    fun getTermuxX11VersionCode(): Long? = getVersionCode(TERMUX_X11_PACKAGE)

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"
    }
}

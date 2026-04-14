package com.runelitetablet.testutil

import com.runelitetablet.domain.installer.PackageChecker

/**
 * Android boundary fake — replaces TermuxPackageHelper.
 * Can't query Android PackageManager on a laptop.
 *
 * Configurable installed/not-installed state per package.
 */
class FakePackageChecker : PackageChecker {
    private val installedPackages = mutableMapOf<String, Long>()

    fun setInstalled(packageName: String, versionCode: Long = 1L) {
        installedPackages[packageName] = versionCode
    }

    fun setNotInstalled(packageName: String) {
        installedPackages.remove(packageName)
    }

    override fun isPackageInstalled(packageName: String): Boolean =
        installedPackages.containsKey(packageName)

    override fun getVersionCode(packageName: String): Long? =
        installedPackages[packageName]
}

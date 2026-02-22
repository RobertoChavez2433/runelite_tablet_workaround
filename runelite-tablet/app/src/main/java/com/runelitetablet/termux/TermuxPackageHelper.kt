package com.runelitetablet.termux

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

class TermuxPackageHelper(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"
    }

    private val packageManager: PackageManager get() = context.packageManager

    fun isTermuxInstalled(): Boolean = isPackageInstalled(TERMUX_PACKAGE)

    fun isTermuxX11Installed(): Boolean = isPackageInstalled(TERMUX_X11_PACKAGE)

    fun getTermuxVersionCode(): Long? = getVersionCode(TERMUX_PACKAGE)

    fun getTermuxX11VersionCode(): Long? = getVersionCode(TERMUX_X11_PACKAGE)

    fun isPackageInstalled(packageName: String): Boolean = getPackageInfoOrNull(packageName) != null

    fun getVersionCode(packageName: String): Long? {
        val info = getPackageInfoOrNull(packageName) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun getPackageInfoOrNull(packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}

package com.runelitetablet.termux

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.runelitetablet.logging.AppLog

class TermuxPackageHelper(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"
    }

    private val packageManager: PackageManager get() = context.packageManager

    fun isTermuxInstalled(): Boolean {
        val result = isPackageInstalled(TERMUX_PACKAGE)
        AppLog.step("termux", "isTermuxInstalled: result=$result package=$TERMUX_PACKAGE")
        return result
    }

    fun isTermuxX11Installed(): Boolean {
        val result = isPackageInstalled(TERMUX_X11_PACKAGE)
        AppLog.step("termux_x11", "isTermuxX11Installed: result=$result package=$TERMUX_X11_PACKAGE")
        return result
    }

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

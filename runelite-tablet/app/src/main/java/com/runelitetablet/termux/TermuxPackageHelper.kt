package com.runelitetablet.termux

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class TermuxPackageHelper(private val context: Context) {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_X11_PACKAGE = "com.termux.x11"
    }

    fun isTermuxInstalled(): Boolean = isPackageInstalled(TERMUX_PACKAGE)

    fun isTermuxX11Installed(): Boolean = isPackageInstalled(TERMUX_X11_PACKAGE)

    fun getTermuxVersionCode(): Long? = getVersionCode(TERMUX_PACKAGE)

    fun getTermuxX11VersionCode(): Long? = getVersionCode(TERMUX_X11_PACKAGE)

    private fun getVersionCode(packageName: String): Long? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

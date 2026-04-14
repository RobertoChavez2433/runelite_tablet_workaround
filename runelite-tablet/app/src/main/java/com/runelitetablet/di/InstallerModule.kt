package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.installer.InstallResultRegistry
import com.runelitetablet.logging.AppLog
import okhttp3.OkHttpClient

class InstallerModule(context: Context, httpClient: OkHttpClient) {
    val installResultRegistry = InstallResultRegistry().also {
        AppLog.d("DI", "InstallerModule: InstallResultRegistry initialized")
    }
    val apkDownloader = ApkDownloader(context, httpClient).also {
        AppLog.d("DI", "InstallerModule: ApkDownloader initialized")
    }
    val apkInstaller = ApkInstaller(context, installResultRegistry).also {
        AppLog.d("DI", "InstallerModule: ApkInstaller initialized")
    }
}
